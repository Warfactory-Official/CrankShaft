package dev.engine_room.flywheel.lib.visual.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.effects.SpearAnimations;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;

public final class HeldItemPoses {
    private HeldItemPoses() {
    }

    /**
     * {@code ItemInHandLayer.submitArmWithItem} up to the item's submit, from the posed model's frame.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void thirdPersonHand(ArmedEntityRenderState state, HumanoidArm arm, ArmedModel posed,
                                       ItemStack stack, PoseStack poseStack) {
        posed.translateToHand(state, arm, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        boolean baby = state.isBaby && state.entityType != EntityTypes.ARMOR_STAND;
        poseStack.translate((arm == HumanoidArm.LEFT ? -1.0F : 1.0F) * (baby ? 0.0F : 1.0F) / 16.0F,
                (baby ? 1.0F : 2.0F) / 16.0F, (baby ? -4.5F : -10.0F) / 16.0F);
        if (state.attackTime > 0.0F && state.attackArm == arm && state.swingAnimationType == SwingAnimationType.STAB) {
            SpearAnimations.thirdPersonAttackItem(state, poseStack);
        }
        float ticksUsingItem = state.ticksUsingItem(arm);
        if (ticksUsingItem != 0.0F) {
            (arm == HumanoidArm.RIGHT ? state.rightArmPose : state.leftArmPose).animateUseItem(state, poseStack,
                    ticksUsingItem, arm, stack);
        }
    }
}
