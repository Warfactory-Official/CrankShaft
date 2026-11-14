package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlGpuTimer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GPU timer scopes per Iris composite program ({@code iris:<pass>}) while the GL timer runs, on Iris's own debug groups.
 */
@Mixin(CompositeRenderer.class)
abstract class CompositePassTimerMixin {
    @Unique
    private int flywheel$timedGroups;

    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;pushGroup(ILjava/lang/String;)V"))
    private void flywheel$pushPass(int id, String name, Operation<Void> original) {
        original.call(id, name);
        if (GlGpuTimer.isEnabled()) {
            GlCompat.pushDebugGroup("iris:" + name);
            flywheel$timedGroups++;
        }
    }

    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/GLDebug;popGroup()V"))
    private void flywheel$popPass(Operation<Void> original) {
        if (flywheel$timedGroups > 0) {
            flywheel$timedGroups--;
            GlCompat.popDebugGroup();
        }
        original.call();
    }
}
