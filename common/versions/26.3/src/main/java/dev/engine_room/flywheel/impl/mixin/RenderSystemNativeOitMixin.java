package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.compile.NativeOitScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
abstract class RenderSystemNativeOitMixin {
    @Inject(method = "getCompiledPipelineNullable", at = @At("HEAD"), cancellable = true)
    private static void flywheel$adaptNativeOit(RenderPipeline pipeline,
                                                CallbackInfoReturnable<CompiledRenderPipeline> cir) {
        CompiledRenderPipeline adapted = NativeOitScope.select(pipeline);
        if (adapted != null) cir.setReturnValue(adapted);
    }
}
