package dev.engine_room.flywheel.lib.model.baked;

import net.minecraft.client.renderer.item.properties.conditional.*;
import net.minecraft.client.renderer.item.properties.numeric.*;
import net.minecraft.client.renderer.item.properties.select.*;

import java.util.Set;

/**
 * The stack-determined item-model property whitelist, mirroring upstream 1.21.1 vanillin's {@code ItemOverrides}.
 */
final class ItemModelProperties {
    static final Set<Class<?>> STACK_DETERMINED = Set.of(
            BundleFullness.class, Cooldown.class, Count.class, CrossbowPull.class, Damage.class,
            UseCycle.class, UseDuration.class,
            net.minecraft.client.renderer.item.properties.numeric.CustomModelDataProperty.class,
            Charge.class, ComponentContents.class, ContextDimension.class, ContextEntityType.class,
            DisplayContext.class, ItemBlockState.class, MainHand.class, TrimMaterialProperty.class,
            net.minecraft.client.renderer.item.properties.select.CustomModelDataProperty.class,
            Broken.class, ComponentMatches.class, Damaged.class, FishingRodCast.class, HasComponent.class,
            IsUsingItem.class,
            net.minecraft.client.renderer.item.properties.conditional.CustomModelDataProperty.class);

    private ItemModelProperties() {
    }
}
