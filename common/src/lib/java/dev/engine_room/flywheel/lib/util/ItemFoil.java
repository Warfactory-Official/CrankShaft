package dev.engine_room.flywheel.lib.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * {@link ItemStack#hasFoil} as vanilla computes it. Compat with Iris: its mixin answers {@code false} while its shadow
 * pass renders, and visuals resolve items off the render thread.
 */
public final class ItemFoil {
    private ItemFoil() {
    }

    public static boolean of(ItemStack stack) {
        Boolean override = stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        return override != null ? override : stack.getItem().isFoil(stack);
    }
}
