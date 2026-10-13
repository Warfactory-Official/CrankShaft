package dev.engine_room.flywheel.iris.experimental.mixin;

import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PackSamplers.class)
abstract class ExperimentalPackSamplersMixin {
    // The heap is immutable after discovery; instance types may first appear after the world has loaded.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$registerGuestSamplers(CallbackInfo ci) {
        PackSamplers pack = (PackSamplers) (Object) this;
        for (int unit = 0; unit < 3; unit++) {
            pack.register(SamplerHeapLayout.Type.SAMPLER2D, "world/texture/" + unit, true);
        }
        pack.register(SamplerHeapLayout.Type.ISAMPLERBUFFER, "flywheel/u_SectionTimeInfo", true);
        pack.register(SamplerHeapLayout.Type.SAMPLER2D, "flywheel/crumbling", true);
    }
}
