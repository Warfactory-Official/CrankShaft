package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.model.effects.EvokerFangsModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.state.EvokerFangsRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.projectile.EvokerFangs;

public final class EvokerFangsVisual extends EntityModelVisual<EvokerFangs, EvokerFangsRenderState> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace(
            "textures/entity/illager/evoker_fangs.png");

    public EvokerFangsVisual(VisualizationContext ctx, EvokerFangs entity, float partialTick) {
        super(ctx, entity, partialTick, ModelLayers.EVOKER_FANGS, EvokerFangsModel::new);
    }

    @Override
    protected Identifier texture(EvokerFangsRenderState state) {
        return TEXTURE;
    }

    @Override
    protected void setupRootPose(PoseStack pose, EvokerFangsRenderState state) {
        pose.mulPose(Axis.YP.rotationDegrees(90.0F - state.yRot));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0.0F, -1.501F, 0.0F);
    }

    @Override
    protected boolean isHidden(EvokerFangsRenderState state) {
        return state.isInvisible || state.biteProgress == 0.0F;
    }
}
