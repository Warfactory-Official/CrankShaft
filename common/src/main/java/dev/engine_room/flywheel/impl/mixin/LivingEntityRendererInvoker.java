package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
    @Invoker("setupRotations")
    void flywheel$setupRotations(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale);

    @Invoker("scale")
    void flywheel$scale(LivingEntityRenderState state, PoseStack poseStack);

    @Invoker("shouldRenderLayers")
    boolean flywheel$shouldRenderLayers(LivingEntityRenderState state);
}
