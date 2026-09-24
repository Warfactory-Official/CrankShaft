package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.object.boat.BoatModel;
import net.minecraft.client.model.object.boat.RaftModel;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.joml.Quaternionf;

/**
 * The hull of {@code AbstractBoatRenderer}; vanilla keeps the water mask, leash, name, flame and shadow.
 */
public final class BoatVisual extends EntityModelVisual<AbstractBoat, BoatRenderState> {
    private final Identifier texture;

    public BoatVisual(VisualizationContext ctx, AbstractBoat entity, float partialTick, ModelLayerLocation layer,
                      boolean raft) {
        super(ctx, entity, partialTick, layer, raft ? RaftModel::new : BoatModel::new);
        texture = layer.model().withPath(path -> "textures/entity/" + path + ".png");
    }

    @Override
    protected Identifier texture(BoatRenderState state) {
        return texture;
    }

    @Override
    protected boolean isHidden(BoatRenderState state) {
        return false;
    }

    @Override
    protected void setupRootPose(PoseStack pose, BoatRenderState state) {
        pose.translate(0.0F, 0.375F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.yRot));
        float hurt = state.hurtTime;
        if (hurt > 0.0F) {
            pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(hurt) * hurt * state.damageTime / 10.0F * state.hurtDir));
        }
        if (!state.isUnderWater && !Mth.equal(state.bubbleAngle, 0.0F)) {
            pose.mulPose(new Quaternionf().setAngleAxis(state.bubbleAngle * (float) (Math.PI / 180.0), 1.0F, 0.0F,
                    1.0F));
        }
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(90.0F));
    }
}
