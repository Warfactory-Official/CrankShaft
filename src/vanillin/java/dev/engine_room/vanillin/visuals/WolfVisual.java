package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.WolfEntityModel;
import net.minecraft.client.model.ModelWolf;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.util.ResourceLocation;

/** Wolf — 3 state skins (wild / tame / angry) in one atlas, selected per instance (read per frame: taming/anger
 *  flip live). Tail rotation feeds the {@code setRotationAngles} age arg (vanilla {@code RenderWolf.handleRotationFloat});
 *  a wet wolf dims via per-instance tint; the dyed collar is a separate {@link WolfCollarLayer}. Sit/shake/interested
 *  posing rides {@code ModelWolf.setLivingAnimations}. Ageable. */
public final class WolfVisual extends AbstractLivingEntityVisual<EntityWolf, ModelWolf> {
    private static final ResourceLocation WOLF = new ResourceLocation("textures/entity/wolf/wolf.png");
    private static final ResourceLocation TAME = new ResourceLocation("textures/entity/wolf/wolf_tame.png");
    private static final ResourceLocation ANGRY = new ResourceLocation("textures/entity/wolf/wolf_angry.png");
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/wolf"), WOLF, TAME, ANGRY);
    private static final Material COLLAR = EntityMaterials.living("textures/entity/wolf/wolf_collar.png");

    public static void register() {
        ATLAS.register();
    }

    public static boolean isInstanceable(EntityWolf entity) {
        return !entity.isInvisible() && ATLAS.ready();
    }

    // Cell add order: wild, tame, angry.
    private static int skinIndex(EntityWolf entity) {
        return entity.isTamed() ? 1 : (entity.isAngry() ? 2 : 0);
    }

    public WolfVisual(VisualizationContext ctx, EntityWolf entity, float partialTick) {
        super(ctx, entity, partialTick, new WolfEntityModel(), ATLAS.material(), "wolf", 0.5F);
        addLayer(new WolfCollarLayer(ctx, entity, instances, new WolfEntityModel(), COLLAR, "wolf:collar", 1));
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityWolf entity) {
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

    // RenderWolf.handleRotationFloat — the 3rd setRotationAngles arg is the tail rotation, not ticksExisted.
    @Override
    protected float handleRotationFloat(float partialTick) {
        return entity.getTailRotation();
    }

    // RenderWolf.doRender dims a wet wolf with color(f,f,f); reproduce as a per-instance gray tint.
    @Override
    protected int tintColor(EntityWolf entity) {
        if (!entity.isWolfWet()) {
            return 0xFFFFFFFF;
        }
        float f = entity.getBrightness() * entity.getShadingWhileWet(1.0F);
        int g = Math.round(f * 255.0F);
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }
}
