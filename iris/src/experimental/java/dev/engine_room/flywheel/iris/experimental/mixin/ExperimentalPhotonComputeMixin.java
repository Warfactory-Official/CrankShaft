package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.compile.patches.PhotonSharedMemory;
import net.irisshaders.iris.pipeline.GraphicsBackend;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ComputeSource.class)
abstract class ExperimentalPhotonComputeMixin {
    @Inject(method = "getSource", at = @At("RETURN"), cancellable = true)
    private void flywheel$packPhotonSharedMemory(CallbackInfoReturnable<Optional<String>> cir) {
        if (GraphicsBackend.isVulkan()) cir.setReturnValue(cir.getReturnValue().map(PhotonSharedMemory::patch));
    }
}
