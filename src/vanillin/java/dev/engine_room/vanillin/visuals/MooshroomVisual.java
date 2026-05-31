package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.QuadrupedEntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import net.minecraft.client.model.ModelCow;
import net.minecraft.entity.passive.EntityMooshroom;

/** Mooshroom — a {@code ModelCow} body (mooshroom texture) plus the three fullbright red-mushroom block-model
 *  decorations ({@link MooshroomMushroomLayer}). Registered separately from the cow; the registry is
 *  exact-class keyed, so {@code EntityMooshroom} (a {@code EntityCow} subclass) gets this visual. */
public final class MooshroomVisual extends SimpleLivingEntityVisual<EntityMooshroom, ModelCow> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/cow/mooshroom.png");

    public MooshroomVisual(VisualizationContext ctx, EntityMooshroom entity, float partialTick) {
        super(ctx, entity, partialTick, new QuadrupedEntityModel<>(ModelCow::new), MATERIAL, "mooshroom", 0.7F, 1.0F, 90.0F, true);
        addLayer(new MooshroomMushroomLayer(ctx, entity, instances, 1));
    }
}
