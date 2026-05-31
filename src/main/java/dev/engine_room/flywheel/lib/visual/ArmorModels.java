package dev.engine_room.flywheel.lib.visual;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;

/**
 * {@link ArmorLayer} bakes a fixed stock-biped shape, so armor supplying a custom Forge {@code ModelBiped} via
 * {@code Item.getArmorModel} forces the whole entity to vanilla. A biped visual pairs {@link #hasCustomArmorModel}
 * into both its hide gate and {@code skipVanillaRender} so they stay exact complements.
 */
public final class ArmorModels {
    /** The four armor slots in render order; shared (read-only) by the armor layer and slot-gating visuals. */
    public static final EntityEquipmentSlot[] ARMOR_SLOTS = {
            EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET
    };
    // getArmorModel sentinel: a return other than this instance means a custom model. Per-thread: the gate runs on
    // both the render thread and the beginFrame ForkJoinPool, and a mod may pose the passed default.
    private static final ThreadLocal<ModelBiped> DEFAULT_MODEL = ThreadLocal.withInitial(ModelBiped::new);

    private ArmorModels() {
    }

    public static boolean hasCustomArmorModel(EntityLivingBase entity) {
        ModelBiped sentinel = null;
        for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemStackFromSlot(slot);
            if (stack.getItem() instanceof ItemArmor) {
                if (sentinel == null) {
                    sentinel = DEFAULT_MODEL.get();
                }
                if (ForgeHooksClient.getArmorModel(entity, stack, slot, sentinel) != sentinel) {
                    return true;
                }
            }
        }
        return false;
    }
}
