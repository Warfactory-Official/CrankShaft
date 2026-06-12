package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Divert vanilla translucent BER/model draws into the engine OIT capture (concerts with PreparedFrameMixin).
@Mixin(RenderTypeFeatureRenderer.class)
abstract class RenderTypeFeatureRendererMixin {
    @WrapOperation(method = "executeGroup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V"), require = 1)
    private void flw$captureTranslucentForOit(PreparedRenderType renderType, StagedVertexBuffer.ExecuteInfo info,
                                              Operation<Void> original) {
        BerTranslucentCapture capture = BerTranslucentCapture.active();
        if (capture != null && capture.tryCapture(renderType, info)) {
            return;
        }
        original.call(renderType, info);
    }
}
