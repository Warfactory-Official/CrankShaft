package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.ArmorModels;
import net.minecraft.entity.monster.EntityZombieVillager;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

public final class ZombieVillagerAtlas {
    private static final ResourceLocation LOCATION = new ResourceLocation(Tags.VANILLIN_ID, "atlas/zombie_villager");
    private static final ResourceLocation[] SKINS = {
            new ResourceLocation("textures/entity/zombie_villager/zombie_villager.png"),
            new ResourceLocation("textures/entity/zombie_villager/zombie_farmer.png"),
            new ResourceLocation("textures/entity/zombie_villager/zombie_librarian.png"),
            new ResourceLocation("textures/entity/zombie_villager/zombie_priest.png"),
            new ResourceLocation("textures/entity/zombie_villager/zombie_smith.png"),
            new ResourceLocation("textures/entity/zombie_villager/zombie_butcher.png"),
    };
    private static final VariantAtlasHolder HOLDER = new VariantAtlasHolder(LOCATION, SKINS);

    private ZombieVillagerAtlas() {
    }

    public static void register() {
        HOLDER.register();
    }

    public static VariantAtlas atlas() {
        return HOLDER.atlas();
    }

    public static Material material() {
        return HOLDER.material();
    }

    public static boolean isVanillaProfession(EntityZombieVillager entity) {
        return HOLDER.contains(entity.getForgeProfession().getZombieSkin());
    }

    /** Exact complement of the skip gate; anything the instanced body/item/armor layers can't draw falls back to vanilla. */
    public static boolean isInstanceable(EntityZombieVillager entity) {
        if (entity.isInvisible() || entity.isConverting() || !isVanillaProfession(entity)) {
            return false;
        }
        // Custom Forge armor models aren't reproduced by the stock biped armor shape.
        if (ArmorModels.hasCustomArmorModel(entity)) {
            return false;
        }
        for (EntityEquipmentSlot slot : ArmorModels.ARMOR_SLOTS) {
            ItemStack stack = entity.getItemStackFromSlot(slot);
            if (!stack.isEmpty()
                    && !(stack.getItem() instanceof ItemArmor && ((ItemArmor) stack.getItem()).getEquipmentSlot() == slot)) {
                return false;
            }
        }
        return true;
    }
}
