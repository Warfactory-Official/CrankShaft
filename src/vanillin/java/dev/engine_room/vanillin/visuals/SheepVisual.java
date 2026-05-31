package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.QuadrupedEntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import net.minecraft.client.model.ModelSheep1;
import net.minecraft.client.model.ModelSheep2;
import net.minecraft.entity.passive.EntitySheep;

public final class SheepVisual extends SimpleLivingEntityVisual<EntitySheep, ModelSheep2> {
    public SheepVisual(VisualizationContext ctx, EntitySheep entity, float partialTick,
                       EntityModel<ModelSheep2> model, Material body, Material wool, String cacheKey,
                       float shadowRadius) {
        super(ctx, entity, partialTick, model, body, cacheKey, shadowRadius, 1.0F, 90.0F, true);
        addLayer(new SheepWoolLayer(ctx, entity, instances,
                new QuadrupedEntityModel<>(ModelSheep1::new), wool, cacheKey + ":wool", 1));
    }
}
