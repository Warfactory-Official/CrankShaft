package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableSet;
import dev.engine_room.flywheel.iris.engine.FarFrontDepth;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(IrisSamplers.class)
abstract class FarFrontDepthSamplersMixin {
    @Inject(method = "addRenderTargetSamplers(Lnet/irisshaders/iris/gl/sampler/SamplerHolder;Ljava/util/function/Supplier;Lnet/irisshaders/iris/targets/RenderTargets;ZLnet/irisshaders/iris/pipeline/WorldRenderingPipeline;)V",
            at = @At("RETURN"))
    private static void flywheel$frontDepthSampler(SamplerHolder holder, Supplier<ImmutableSet<Integer>> _,
                                                   RenderTargets targets, boolean _,
                                                   WorldRenderingPipeline _, CallbackInfo _) {
        if (holder.hasSampler("flw_farFrontDepth")) {
            FarFrontDepth.texture(targets);
            holder.addDynamicSampler(TextureType.TEXTURE_2D, () -> FarFrontDepth.texture(targets), null,
                    "flw_farFrontDepth");
        }
    }
}
