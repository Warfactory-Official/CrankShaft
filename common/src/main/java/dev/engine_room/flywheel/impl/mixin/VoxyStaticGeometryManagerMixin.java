package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.far.VoxyStaticSections;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Associates native allocation changes with the result packet that installs their GPU metadata.
 */
@Mixin(BasicAsyncGeometryManager.class)
abstract class VoxyStaticGeometryManagerMixin implements VoxyStaticVisuals.GeometryAccess {
    @Unique
    private VoxyStaticSections flywheel$staticSections;

    @Override
    public VoxyStaticSections flywheel$staticSections() {
        return flywheel$staticSections;
    }

    @Override
    public void flywheel$staticSections(VoxyStaticSections sections) {
        flywheel$staticSections = sections;
    }

    @Inject(method = "uploadReplaceSection", at = @At("RETURN"))
    private void flywheel$nativeGeometryAllocated(int previous, BuiltSection source,
                                                  CallbackInfoReturnable<Integer> callback) {
        flywheel$staticSections.nativeGeometry(callback.getReturnValueI(), source);
    }

    @Inject(method = "writeMetadataSplit", at = @At("TAIL"))
    private void flywheel$nativeMetadataWritten(int section, long first, long second, CallbackInfo _) {
        flywheel$staticSections.nativeGeometryWritten(section);
    }

    @Inject(method = "removeSection", at = @At("HEAD"))
    private void flywheel$nativeGeometryRemoved(int section, CallbackInfo _) {
        flywheel$staticSections.nativeGeometryRemoved(section);
    }
}

#endif
