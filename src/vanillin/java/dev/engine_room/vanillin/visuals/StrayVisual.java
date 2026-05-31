package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.BipedEntityModel;
import dev.engine_room.flywheel.lib.visual.BipedLivingEntityVisual;
import net.minecraft.client.model.ModelSkeleton;
import net.minecraft.entity.monster.EntityStray;

public final class StrayVisual extends BipedLivingEntityVisual<EntityStray, ModelSkeleton> {
    private static final Material BODY = EntityMaterials.living("textures/entity/skeleton/stray.png");
    private static final Material CLOTHING = EntityMaterials.living("textures/entity/skeleton/stray_overlay.png");

    public StrayVisual(VisualizationContext ctx, EntityStray entity, float partialTick) {
        super(ctx, entity, partialTick, new BipedEntityModel<>(ModelSkeleton::new), BODY, "stray", 0.5F, 1.0F, 90.0F);
        addLayer(new StrayClothingLayer(ctx, entity, instances,
                new BipedEntityModel<>(() -> new ModelSkeleton(0.25F, true)), CLOTHING, "stray:clothing", 1));
    }
}
