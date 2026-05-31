package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.QuadrupedEntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import net.minecraft.client.model.ModelPig;
import net.minecraft.entity.passive.EntityPig;

public final class PigVisual extends SimpleLivingEntityVisual<EntityPig, ModelPig> {
    private static final Material BODY = EntityMaterials.living("textures/entity/pig/pig.png");
    private static final Material SADDLE = EntityMaterials.living("textures/entity/pig/pig_saddle.png");

    public PigVisual(VisualizationContext ctx, EntityPig entity, float partialTick) {
        super(ctx, entity, partialTick, new QuadrupedEntityModel<>(ModelPig::new), BODY, "pig", 0.7F, 1.0F, 90.0F, true);
        addLayer(new SaddleLayer(ctx, entity, instances,
                new QuadrupedEntityModel<>(() -> new ModelPig(0.5F)), SADDLE, "pig:saddle", 1));
    }
}
