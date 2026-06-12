package dev.engine_room.vanillin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ThrownTridentRenderer;
import net.minecraft.client.renderer.entity.state.ThrownTridentRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import org.jspecify.annotations.Nullable;

public class TridentVisual extends EntityModelVisual<ThrownTrident, ThrownTridentRenderState> {
    static final Material MATERIAL = SimpleMaterial.builder()
                                                   .mipmap(false)
                                                   .texture(ThrownTridentRenderer.TRIDENT_LOCATION)
                                                   .build();

    public TridentVisual(VisualizationContext ctx, ThrownTrident entity, float partialTick) {
        super(ctx, entity, partialTick, ModelLayers.TRIDENT, root -> new EntityModel<ThrownTridentRenderState>(root) {
        });
    }

    @Override
    protected Identifier texture(ThrownTridentRenderState state) {
        return ThrownTridentRenderer.TRIDENT_LOCATION;
    }

    @Override
    protected Material material(Identifier texture) {
        return MATERIAL;
    }

    @Override
    protected void setupRootPose(PoseStack pose, ThrownTridentRenderState state) {
        pose.mulPose(Axis.YP.rotationDegrees(state.yRot - 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(state.xRot + 90.0F));
    }

    @Override
    @Nullable
    protected Material foilMaterial(ThrownTridentRenderState state) {
        return state.isFoil ? Materials.GLINT_ENTITY : null;
    }
}
