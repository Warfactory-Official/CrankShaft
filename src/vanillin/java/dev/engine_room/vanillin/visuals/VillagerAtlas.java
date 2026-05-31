package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.util.ResourceLocation;

public final class VillagerAtlas {
    private static final ResourceLocation LOCATION = new ResourceLocation(Tags.VANILLIN_ID, "atlas/villager");
    private static final ResourceLocation[] SKINS = {
            new ResourceLocation("textures/entity/villager/villager.png"),
            new ResourceLocation("textures/entity/villager/farmer.png"),
            new ResourceLocation("textures/entity/villager/librarian.png"),
            new ResourceLocation("textures/entity/villager/priest.png"),
            new ResourceLocation("textures/entity/villager/smith.png"),
            new ResourceLocation("textures/entity/villager/butcher.png"),
    };
    private static final VariantAtlasHolder HOLDER = new VariantAtlasHolder(LOCATION, SKINS);

    private VillagerAtlas() {
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

    public static boolean isVanillaProfession(EntityVillager entity) {
        return HOLDER.contains(entity.getProfessionForge().getSkin());
    }

    public static boolean isInstanceable(EntityVillager entity) {
        return !entity.isInvisible()
                && isVanillaProfession(entity)
                && entity.getItemStackFromSlot(EntityEquipmentSlot.HEAD).isEmpty();
    }
}
