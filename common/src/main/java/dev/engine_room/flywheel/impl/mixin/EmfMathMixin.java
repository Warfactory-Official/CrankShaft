package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import traben.entity_model_features.EMFAnimationApi;
import traben.entity_model_features.models.animation.math.EMFMath;

import java.util.function.Predicate;

@Mixin(EMFMath.class)
abstract class EmfMathMixin {
    // EMF's is_using_item reads the live entity, while an item's authored arm pose can aim without gameplay use.
    @ModifyReturnValue(method = "isUsingItem", at = @At("RETURN"))
    private static boolean flywheel$heldUse(boolean original) {
        if (original) return true;
        Predicate<LivingEntity> condition = EntityFeatureCompat.emfHeldUseCondition();
        return condition != null && EMFAnimationApi.getCurrentEntity() instanceof LivingEntity entity
                && condition.test(entity);
    }
}
