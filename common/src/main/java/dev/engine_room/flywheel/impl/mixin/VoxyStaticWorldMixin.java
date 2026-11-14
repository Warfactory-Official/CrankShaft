package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.far.VoxyStaticSections;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VoxyRenderSystem.class)
abstract class VoxyStaticWorldMixin implements VoxyStaticVisuals.WorldAccess {
    @Shadow
    @Final
    private AsyncNodeManager nodeManager;

    @Override
    public VoxyStaticSections flywheel$staticSections() {
        return nodeManager.flywheel$staticSections();
    }

    @Inject(method = "renderOpaque", at = @At("HEAD"))
    private void flywheel$syncStaticSections(Viewport<?> viewport, int depth, int colour, CallbackInfo _) {
        flywheel$staticSections().sync();
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void flywheel$freeStaticSections(CallbackInfo _) {
        flywheel$staticSections().close();
    }
}

#endif
