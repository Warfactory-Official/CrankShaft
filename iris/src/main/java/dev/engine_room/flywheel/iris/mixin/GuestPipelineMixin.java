package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Wraps the whole method so Iris's HEAD pipeline override (which logs every unmapped pipeline) never sees a guest.
 */
@Mixin(GlDevice.class)
abstract class GuestPipelineMixin {
    @WrapMethod(method = "getOrCompilePipeline")
    private GlRenderPipeline flywheel$guestPipeline(RenderPipeline pipeline, Operation<GlRenderPipeline> original) {
        GlRenderPipeline guest = (GlRenderPipeline) GuestPipelines.compiled(pipeline);
        return guest != null ? guest : original.call(pipeline);
    }

    @WrapMethod(method = "precompilePipeline")
    private GlRenderPipeline flywheel$guestPrecompile(RenderPipeline pipeline, @Nullable ShaderSource shaderSource,
                                                      Operation<GlRenderPipeline> original) {
        GlRenderPipeline guest = (GlRenderPipeline) GuestPipelines.compiled(pipeline);
        return guest != null ? guest : original.call(pipeline, shaderSource);
    }
}
