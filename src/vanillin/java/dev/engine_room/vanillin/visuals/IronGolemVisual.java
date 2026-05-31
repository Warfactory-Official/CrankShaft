package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.IronGolemEntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import net.minecraft.client.model.ModelIronGolem;
import net.minecraft.entity.monster.EntityIronGolem;
import org.joml.Matrix4f;

public final class IronGolemVisual extends SimpleLivingEntityVisual<EntityIronGolem, ModelIronGolem> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/iron_golem.png");

    public IronGolemVisual(VisualizationContext ctx, EntityIronGolem entity, float partialTick) {
        super(ctx, entity, partialTick, new IronGolemEntityModel(), MATERIAL, "iron_golem", 0.5F, 1.0F, 90.0F);
        addLayer(new IronGolemRoseLayer(ctx, entity, model, 1));
    }

    @Override
    protected void applyRotations(Matrix4f dest, float bodyYaw, float partialTick) {
        super.applyRotations(dest, bodyYaw, partialTick);
        if (entity.limbSwingAmount >= 0.01F) {
            float f1 = entity.limbSwing - entity.limbSwingAmount * (1.0F - partialTick) + 6.0F;
            float f2 = (Math.abs(f1 % 13.0F - 6.5F) - 3.25F) / 3.25F;
            dest.rotateZ((float) Math.toRadians(6.5F * f2));
        }
    }
}
