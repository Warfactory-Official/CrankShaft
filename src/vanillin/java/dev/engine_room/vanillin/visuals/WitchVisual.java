package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.WitchEntityModel;
import net.minecraft.client.model.ModelWitch;
import net.minecraft.entity.monster.EntityWitch;
import org.joml.Matrix4f;

public final class WitchVisual extends AbstractLivingEntityVisual<EntityWitch, ModelWitch> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/witch.png");

    public WitchVisual(VisualizationContext ctx, EntityWitch entity, float partialTick) {
        super(ctx, entity, partialTick, new WitchEntityModel(), MATERIAL, "vanillin:witch", 0.5F);
        addLayer(new WitchPotionLayer(ctx, entity, model, 1));
    }

    @Override
    protected void poseModel(float partialTick) {
        model.holdingItem = !entity.getHeldItemMainhand().isEmpty();
        super.poseModel(partialTick);
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(0.9375F);
    }
}
