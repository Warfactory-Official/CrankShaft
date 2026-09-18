package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Wraps the whole method so Iris's HEAD pipeline override (which logs every unmapped pipeline) never sees a guest.
 */
@Mixin(GlDevice.class)
abstract class GlDeviceMixin {
    @WrapMethod(method = "getOrCompilePipeline")
    private GlRenderPipeline flywheel$guestPipeline(RenderPipeline pipeline, Operation<GlRenderPipeline> original) {
        GlRenderPipeline guest = GuestPipelines.compiled(pipeline);
        return guest != null ? guest : original.call(pipeline);
    }

    @WrapMethod(method = "precompilePipeline")
    private GlRenderPipeline flywheel$guestPrecompile(RenderPipeline pipeline, @Nullable ShaderSource shaderSource,
                                                      Operation<GlRenderPipeline> original) {
        GlRenderPipeline guest = GuestPipelines.compiled(pipeline);
        return guest != null ? guest : original.call(pipeline, shaderSource);
    }
}
