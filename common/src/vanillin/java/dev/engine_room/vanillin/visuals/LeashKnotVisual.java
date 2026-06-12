package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.leash.LeashKnotModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;

public final class LeashKnotVisual extends EntityModelVisual<LeashFenceKnotEntity, EntityRenderState> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace(
            "textures/entity/lead_knot/lead_knot.png");

    public LeashKnotVisual(VisualizationContext ctx, LeashFenceKnotEntity entity, float partialTick) {
        super(ctx, entity, partialTick, ModelLayers.LEASH_KNOT, LeashKnotModel::new);
    }

    @Override
    protected Identifier texture(EntityRenderState state) {
        return TEXTURE;
    }

    @Override
    protected void setupRootPose(PoseStack pose, EntityRenderState state) {
        pose.scale(-1.0F, -1.0F, 1.0F);
    }
}
