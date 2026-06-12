package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

public final class EndCrystalVisual extends EntityModelVisual<EndCrystal, EndCrystalRenderState> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace(
            "textures/entity/end_crystal/end_crystal.png");

    public EndCrystalVisual(VisualizationContext ctx, EndCrystal entity, float partialTick) {
        super(ctx, entity, partialTick, ModelLayers.END_CRYSTAL, EndCrystalModel::new);
    }

    public static boolean isSupported(EndCrystal entity) {
        return entity.getBeamTarget() == null;
    }

    @Override
    protected Identifier texture(EndCrystalRenderState state) {
        return TEXTURE;
    }

    @Override
    protected void setupRootPose(PoseStack pose, EndCrystalRenderState state) {
        pose.scale(2.0F, 2.0F, 2.0F);
        pose.translate(0.0F, -0.5F, 0.0F);
    }

    @Override
    protected boolean isHidden(EndCrystalRenderState state) {
        return state.isInvisible || state.beamOffset != null;
    }

    @Override
    protected float shadowRadius() {
        return 0.5F;
    }
}
