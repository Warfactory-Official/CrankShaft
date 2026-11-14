package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * A native-depth pack's hardware shadow comparisons follow its reversed shadow depth.
 */
@Mixin(ShadowRenderTargets.class)
abstract class NativeShadowSamplersMixin {
    // The hardware samplers are shared statics, so each pipeline's shadow targets set them for the current pack.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$hardwareCompare(CallbackInfo ci) {
        int func = NativeDepth.active() ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL;
        for (GlSampler sampler : List.of(GlSampler.MIPPED_LINEAR_HW, GlSampler.LINEAR_HW, GlSampler.MIPPED_NEAREST_HW,
                GlSampler.NEAREST_HW)) {
            IrisRenderSystem.samplerParameteri(sampler.getId(), GL20C.GL_TEXTURE_COMPARE_FUNC, func);
        }
    }
}
