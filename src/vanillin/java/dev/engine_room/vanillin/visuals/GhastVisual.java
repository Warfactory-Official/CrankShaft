package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.GhastEntityModel;
import net.minecraft.client.model.ModelGhast;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

public final class GhastVisual extends AbstractLivingEntityVisual<EntityGhast, ModelGhast> {
    private static final ResourceLocation IDLE = new ResourceLocation("textures/entity/ghast/ghast.png");
    private static final ResourceLocation SHOOTING = new ResourceLocation("textures/entity/ghast/ghast_shooting.png");
    private static final VariantAtlasHolder ATLAS = new VariantAtlasHolder(new ResourceLocation(Tags.VANILLIN_ID, "atlas/ghast"), IDLE, SHOOTING);

    public static void register() {
        ATLAS.register();
    }

    public static boolean isInstanceable(EntityGhast entity) {
        return !entity.isInvisible() && ATLAS.ready();
    }

    public GhastVisual(VisualizationContext ctx, EntityGhast entity, float partialTick) {
        super(ctx, entity, partialTick, new GhastEntityModel(), ATLAS.material(), "ghast", 0.5F);
    }

    @Override
    protected InstanceType<? extends TransformedInstance> instanceType() {
        return InstanceTypes.UV_TRANSFORMED;
    }

    @Override
    protected VariantAtlas.Cell uvRegion(EntityGhast entity) {
        // Cell add order: idle, shooting.
        return ATLAS.cell(entity.isAttacking() ? 1 : 0);
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable(entity);
    }

    // RenderGhast.preRenderCallback scale 4.5; ModelGhast.render translate(0,0.6,0) — both in scaled space.
    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(4.5F);
        dest.translate(0.0F, 0.6F, 0.0F);
    }
}
