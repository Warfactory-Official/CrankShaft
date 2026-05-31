package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.SnowGolemEntityModel;
import net.minecraft.client.model.ModelSnowMan;
import net.minecraft.entity.monster.EntitySnowman;

public final class SnowGolemVisual extends SimpleLivingEntityVisual<EntitySnowman, ModelSnowMan> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/snowman.png");

    public SnowGolemVisual(VisualizationContext ctx, EntitySnowman entity, float partialTick) {
        super(ctx, entity, partialTick, new SnowGolemEntityModel(), MATERIAL, "snow_golem", 0.5F, 1.0F, 90.0F);
        addLayer(new SnowGolemPumpkinLayer(ctx, entity, instances, 1));
    }
}
