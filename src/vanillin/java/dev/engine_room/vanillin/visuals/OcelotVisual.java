package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.OcelotEntityModel;
import net.minecraft.client.model.ModelOcelot;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

/** Ocelot / cat — 4 skins (wild ocelot + 3 tamed cats) in one atlas, selected per instance by
 *  {@code getTameSkin()} (read per frame: taming flips it live). Tamed cats render at 0.8 scale. Ageable. */
public final class OcelotVisual extends AbstractLivingEntityVisual<EntityOcelot, ModelOcelot> {
    // Indexed by getTameSkin() (0/default..3), matching RenderOcelot.getEntityTexture.
    private static final ResourceLocation[] SKINS = {
            new ResourceLocation("textures/entity/cat/ocelot.png"),
            new ResourceLocation("textures/entity/cat/black.png"),
            new ResourceLocation("textures/entity/cat/red.png"),
            new ResourceLocation("textures/entity/cat/siamese.png"),
    };
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/ocelot"), SKINS);

    public static void register() {
        ATLAS.register();
    }

    public static boolean isInstanceable(EntityOcelot entity) {
        return !entity.isInvisible() && ATLAS.ready();
    }

    private static int skinIndex(EntityOcelot entity) {
        int t = entity.getTameSkin();
        return t < 0 || t >= SKINS.length ? 0 : t;
    }

    public OcelotVisual(VisualizationContext ctx, EntityOcelot entity, float partialTick) {
        super(ctx, entity, partialTick, new OcelotEntityModel(), ATLAS.material(), "ocelot", 0.4F);
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityOcelot entity) {
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

    // RenderOcelot.preRenderCallback: tamed cats shrink to 0.8.
    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        if (entity.isTamed()) {
            dest.scale(0.8F);
        }
    }
}
