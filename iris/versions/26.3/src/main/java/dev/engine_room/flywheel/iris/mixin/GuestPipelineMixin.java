package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import org.spongepowered.asm.mixin.Mixin;

/** Claims guests before the engine's native cache, including terrain pipelines warmed before a pack was loaded. */
@Mixin(RenderPipelineCompiler.class)
abstract class GuestPipelineMixin {
    @WrapMethod(method = "get")
    private static CompiledRenderPipeline flywheel$guestPipeline(RenderPipeline pipeline,
                                                                Operation<CompiledRenderPipeline> original) {
        CompiledRenderPipeline guest = GuestPipelines.compiled(pipeline);
        return guest != null ? guest : original.call(pipeline);
    }
}
