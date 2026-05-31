package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.ChickenEntityModel;
import net.minecraft.client.model.ModelChicken;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.util.math.MathHelper;

public final class ChickenVisual extends AbstractLivingEntityVisual<EntityChicken, ModelChicken> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/chicken.png");

    public ChickenVisual(VisualizationContext ctx, EntityChicken entity, float partialTick) {
        super(ctx, entity, partialTick, new ChickenEntityModel(), MATERIAL, "vanillin:chicken", 0.3F);
    }

    @Override
    protected boolean instancesBabies() {
        return true;
    }

    @Override
    protected float handleRotationFloat(float partialTick) {
        float flap = entity.oFlap + (entity.wingRotation - entity.oFlap) * partialTick;
        float flapSpeed = entity.oFlapSpeed + (entity.destPos - entity.oFlapSpeed) * partialTick;
        return (MathHelper.sin(flap) + 1.0F) * flapSpeed;
    }
}
