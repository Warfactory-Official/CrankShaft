package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.impl.visualization.PrimarySkipState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The bobber (first custom geometry) only: the line keeps vanilla's arm resolution, width and target.
@Mixin(FishingHookRenderer.class)
abstract class FishingHookRendererMixin {
    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V",
                    ordinal = 0))
    private void flywheel$skipInstancedBobber(SubmitNodeCollector collector, PoseStack poseStack,
                                              RenderType renderType,
                                              SubmitNodeCollector.CustomGeometryRenderer geometry,
                                              Operation<Void> original, FishingHookRenderState state) {
        if (!((PrimarySkipState) state).flywheel$skipPrimary()) {
            original.call(collector, poseStack, renderType, geometry);
        }
    }
}
