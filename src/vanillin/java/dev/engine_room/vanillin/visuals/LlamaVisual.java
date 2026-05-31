package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.LlamaEntityModel;
import net.minecraft.client.model.ModelLlama;
import net.minecraft.entity.passive.EntityLlama;
import net.minecraft.util.ResourceLocation;

/** Llama — 4 coat variants in one atlas (per-instance UV by {@code getVariant}); the two chest boxes toggle per
 *  frame on {@code hasChest}; a carpeted llama ({@code hasColor}) gets a 16-dye decor overlay ({@link
 *  LlamaDecorLayer}). Ageable. */
public final class LlamaVisual extends AbstractLivingEntityVisual<EntityLlama, ModelLlama> {
    private static final ResourceLocation[] SKINS = {
            new ResourceLocation("textures/entity/llama/llama_creamy.png"),
            new ResourceLocation("textures/entity/llama/llama_white.png"),
            new ResourceLocation("textures/entity/llama/llama_brown.png"),
            new ResourceLocation("textures/entity/llama/llama_gray.png"),
    };
    // Indexed by EnumDyeColor.getMetadata() (0..15), matching LayerLlamaDecor.LLAMA_DECOR_TEXTURES.
    static final ResourceLocation[] DECOR_SKINS = {
            new ResourceLocation("textures/entity/llama/decor/decor_white.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_orange.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_magenta.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_light_blue.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_yellow.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_lime.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_pink.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_gray.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_silver.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_cyan.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_purple.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_blue.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_brown.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_green.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_red.png"),
            new ResourceLocation("textures/entity/llama/decor/decor_black.png"),
    };
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/llama"), SKINS);
    private static final VariantAtlasHolder DECOR_ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/llama_decor"), DECOR_SKINS);
    private static final int CHEST_ROOT = 6;

    public static void register() {
        ATLAS.register();
        DECOR_ATLAS.register();
    }

    public static boolean isInstanceable(EntityLlama entity) {
        return !entity.isInvisible() && ATLAS.ready();
    }

    private static int skinIndex(EntityLlama entity) {
        int v = entity.getVariant();
        return v < 0 || v >= SKINS.length ? 0 : v;
    }

    public LlamaVisual(VisualizationContext ctx, EntityLlama entity, float partialTick) {
        super(ctx, entity, partialTick, new LlamaEntityModel(), ATLAS.material(), "llama", 0.7F);
        addLayer(new LlamaDecorLayer(ctx, entity, instances, DECOR_ATLAS, 1));
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityLlama entity) {
        return ATLAS.cell(skinIndex(entity));
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable(entity);
    }

    @Override
    protected boolean instancesBabies() {
        return true;
    }

    @Override
    protected void poseModel(float partialTick) {
        super.poseModel(partialTick);
        // Vanilla gates chests on !isChild too.
        boolean chest = !entity.isChild() && entity.hasChest();
        setRootSkipDraw(CHEST_ROOT, !chest);
        setRootSkipDraw(CHEST_ROOT + 1, !chest);
    }
}
