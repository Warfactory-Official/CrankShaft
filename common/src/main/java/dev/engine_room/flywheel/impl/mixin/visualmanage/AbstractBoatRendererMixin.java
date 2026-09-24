package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.impl.visualization.PrimarySkipState;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The hull only: the water mask (submitTypeAdditions), leash, name, flame and shadow stay vanilla.
@Mixin(AbstractBoatRenderer.class)
abstract class AbstractBoatRendererMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/BoatRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/Identifier;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private void flywheel$skipInstancedHull(SubmitNodeCollector collector, Model model, Object state,
                                            PoseStack poseStack, Identifier texture, int lightCoords,
                                            int overlayCoords, int outlineColor,
                                            ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
                                            Operation<Void> original) {
        if (!((PrimarySkipState) state).flywheel$skipPrimary()) {
            original.call(collector, model, state, poseStack, texture, lightCoords, overlayCoords, outlineColor,
                    crumblingOverlay);
            return;
        }
        if (outlineColor != 0) {
            // An outline render type submits to the outline phase only.
            model.renderType(texture).outline().ifPresent(outline -> collector.submitModel(model, state, poseStack,
                    outline, lightCoords, overlayCoords, -1, null, outlineColor, null));
        }
    }
}
