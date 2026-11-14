package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.impl.far.VoxyStaticSyncPayload;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.VarHandle;

@Mixin(AsyncNodeManager.class)
abstract class VoxyStaticPublicationMixin {
    @Unique
    private Long2LongMap flywheel$appliedRevisions = Long2LongMaps.EMPTY_MAP;

    // The journal joins the same packet as its geometry and node scatter writes, including unpublished merges.
    @WrapOperation(method = "run", at = @At(value = "INVOKE",
            target = "Ljava/lang/invoke/VarHandle;compareAndSet"))
    private boolean flywheel$publishRevisions(VarHandle handle, AsyncNodeManager owner,
                                              @Coerce Object expected, @Coerce Object packet,
                                              Operation<Boolean> original) {
        ((VoxyStaticSyncPayload) packet).flywheel$mergeStaticRevisions(
                owner.flywheel$staticSections().drainNativeRevisions());
        return original.call(handle, owner, expected, packet);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void flywheel$beginApplication(GlBuffer nodes, NodeCleaner cleaner, CallbackInfo ci) {
        flywheel$appliedRevisions = Long2LongMaps.EMPTY_MAP;
    }

    // Detach immediately: tick returns the packet to the worker cache before this method's RETURN hook.
    @Coerce
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Ljava/lang/invoke/VarHandle;getAndSet"))
    private Object flywheel$acquireRevisions(VarHandle handle, AsyncNodeManager owner, @Coerce Object value,
                                             Operation<Object> original) {
        Object packet = original.call(handle, owner, value);
        if (packet != null) {
            flywheel$appliedRevisions = ((VoxyStaticSyncPayload) packet).flywheel$takeStaticRevisions();
        }
        return packet;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void flywheel$completeApplication(GlBuffer nodes, NodeCleaner cleaner, CallbackInfo ci) {
        if (!flywheel$appliedRevisions.isEmpty()) {
            var owner = (AsyncNodeManager) (Object) this;
            owner.flywheel$staticSections().nativeApplied(flywheel$appliedRevisions);
            flywheel$appliedRevisions = Long2LongMaps.EMPTY_MAP;
        }
    }
}

#endif
