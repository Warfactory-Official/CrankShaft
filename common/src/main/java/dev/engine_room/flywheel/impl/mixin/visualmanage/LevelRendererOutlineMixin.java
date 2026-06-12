package dev.engine_room.flywheel.impl.mixin.visualmanage;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.impl.visualization.EntityOutlineSubmits;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererOutlineMixin {
    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Inject(method = "submitEntities", at = @At("TAIL"), require = 1)
    private void flw$submitOutlineOnlyEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                               SubmitNodeCollector output, CallbackInfo ci) {
        EntityOutlineSubmits.submit(entityRenderDispatcher, poseStack, levelRenderState, output);
    }
}
