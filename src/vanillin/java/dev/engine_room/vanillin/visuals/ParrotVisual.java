package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.ParrotEntityModel;
import net.minecraft.client.model.ModelParrot;
import net.minecraft.entity.passive.EntityParrot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

/** Parrot — 5 colour variants in one atlas (per-instance UV by {@code getVariant()}); the flap-bob feeds the
 *  {@code setRotationAngles} age arg via {@code handleRotationFloat} (like the chicken). Non-ageable; shoulder-
 *  riding parrots are drawn by vanilla's separate layer and are unaffected. */
public final class ParrotVisual extends AbstractLivingEntityVisual<EntityParrot, ModelParrot> {
    private static final ResourceLocation[] SKINS = {
            new ResourceLocation("textures/entity/parrot/parrot_red_blue.png"),
            new ResourceLocation("textures/entity/parrot/parrot_blue.png"),
            new ResourceLocation("textures/entity/parrot/parrot_green.png"),
            new ResourceLocation("textures/entity/parrot/parrot_yellow_blue.png"),
            new ResourceLocation("textures/entity/parrot/parrot_grey.png"),
    };
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/parrot"), SKINS);

    public static void register() {
        ATLAS.register();
    }

    public static boolean isInstanceable(EntityParrot entity) {
        return !entity.isInvisible() && ATLAS.ready();
    }

    private static int skinIndex(EntityParrot entity) {
        int v = entity.getVariant();
        return v < 0 || v >= SKINS.length ? 0 : v;
    }

    public ParrotVisual(VisualizationContext ctx, EntityParrot entity, float partialTick) {
        super(ctx, entity, partialTick, new ParrotEntityModel(), ATLAS.material(), "parrot", 0.3F);
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityParrot entity) {
        return ATLAS.cell(skinIndex(entity));
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable(entity);
    }

    @Override
    protected float handleRotationFloat(float partialTick) {
        float f = entity.oFlap + (entity.flap - entity.oFlap) * partialTick;
        float f1 = entity.oFlapSpeed + (entity.flapSpeed - entity.oFlapSpeed) * partialTick;
        return (MathHelper.sin(f) + 1.0F) * f1;
    }
}
