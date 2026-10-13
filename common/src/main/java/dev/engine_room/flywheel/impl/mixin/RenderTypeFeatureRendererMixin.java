package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
#endif
import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Divert vanilla translucent BER/model draws into the engine OIT capture (concerts with PreparedFrameMixin).
@Mixin(RenderTypeFeatureRenderer.class)
abstract class RenderTypeFeatureRendererMixin {
    #if MC >= 26.3
    @WrapOperation(method = "executeGroup",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
            require = 1)
    #else
    @WrapOperation(method = "executeGroup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V"), require = 1)
    #endif
    private void flywheel$captureTranslucentForOit(PreparedRenderType renderType, StagedVertexBuffer.ExecuteInfo info,
                                                   #if MC >= 26.3
                                                   RenderPass renderPass,
                                                   #endif
                                                   Operation<Void> original) {
        BerTranslucentCapture capture = BerTranslucentCapture.active();
        if (capture != null && capture.tryCapture(renderType, info)) {
            return;
        }
        original.call(renderType, info #if MC >= 26.3 , renderPass #endif);
    }
}
