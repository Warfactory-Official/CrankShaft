package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.HorseEntityModel;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.HorseArmorType;
import net.minecraft.util.ResourceLocation;

/**
 * The plain horse ({@link EntityHorse}) — its body texture is a runtime composite of a coat (7) and an optional
 * markings overlay (5), so the 35 combinations are stitched into one atlas at load via {@code addLayered}
 * (mirroring vanilla {@code RenderHorse}'s {@code LayeredTexture}); each instance picks its cell by
 * {@code getHorseVariant}. Saddle/rein tack toggles like the rest of the family (its pixels live in the same coat
 * sheet, so they ride the same cell). Armor rides {@link HorseArmorLayer}; custom armor falls back to vanilla.
 */
public final class HorseVisual extends HorseFamilyVisual {
    private static final String[] COATS = {
            "textures/entity/horse/horse_white.png",
            "textures/entity/horse/horse_creamy.png",
            "textures/entity/horse/horse_chestnut.png",
            "textures/entity/horse/horse_brown.png",
            "textures/entity/horse/horse_black.png",
            "textures/entity/horse/horse_gray.png",
            "textures/entity/horse/horse_darkbrown.png",
    };
    // Index 0 = no markings (base coat only); matches EntityHorse.HORSE_MARKING_TEXTURES.
    private static final String[] MARKINGS = {
            null,
            "textures/entity/horse/horse_markings_white.png",
            "textures/entity/horse/horse_markings_whitefield.png",
            "textures/entity/horse/horse_markings_whitedots.png",
            "textures/entity/horse/horse_markings_blackdots.png",
    };
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(
            new ResourceLocation(Tags.VANILLIN_ID, "atlas/horse"),
            builder -> {
                for (int c = 0; c < COATS.length; c++) {
                    ResourceLocation coat = new ResourceLocation(COATS[c]);
                    for (int m = 0; m < MARKINGS.length; m++) {
                        ResourceLocation marking = MARKINGS[m] == null ? null : new ResourceLocation(MARKINGS[m]);
                        builder.addLayered(key(c, m), coat, marking);
                    }
                }
            });
    private static final VariantAtlasHolder ARMOR_ATLAS = new VariantAtlasHolder(
            new ResourceLocation(Tags.VANILLIN_ID, "atlas/horse_armor"),
            new ResourceLocation(HorseArmorType.IRON.getTextureName()),
            new ResourceLocation(HorseArmorType.GOLD.getTextureName()),
            new ResourceLocation(HorseArmorType.DIAMOND.getTextureName()));

    public static void register() {
        ATLAS.register();
        ARMOR_ATLAS.register();
    }

    public static boolean isInstanceable(EntityHorse entity) {
        if (entity.isInvisible()) {
            return false;
        }
        ResourceLocation armor = HorseArmorLayer.skinFor(entity);
        return armor == null || ARMOR_ATLAS.contains(armor);
    }

    private static ResourceLocation key(int color, int marking) {
        return new ResourceLocation(Tags.VANILLIN_ID, "horse_" + color + "_" + marking);
    }

    public HorseVisual(VisualizationContext ctx, EntityHorse entity, float partialTick) {
        this(ctx, entity, partialTick, new HorseEntityModel(false));
    }

    private HorseVisual(VisualizationContext ctx, EntityHorse entity, float partialTick, HorseEntityModel model) {
        super(ctx, entity, partialTick, model, ATLAS.material(), "horse", 1.0F, -1, model.tackStart());
        addLayer(new HorseArmorLayer(ctx, entity, instances, ARMOR_ATLAS, 1));
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(AbstractHorse entity) {
        // Cells are added coat-major (see the populator), so the index is coat * MARKINGS.length + marking.
        int variant = ((EntityHorse) entity).getHorseVariant();
        int coat = (variant & 0xFF) % COATS.length;
        int marking = ((variant & 0xFF00) >> 8) % MARKINGS.length;
        return ATLAS.cell(coat * MARKINGS.length + marking);
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable((EntityHorse) entity);
    }
}
