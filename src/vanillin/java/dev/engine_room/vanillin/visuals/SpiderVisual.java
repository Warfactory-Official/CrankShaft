package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.EmissiveLayer;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import net.minecraft.client.model.ModelSpider;
import net.minecraft.entity.monster.EntitySpider;

/** Shared by spider and cave spider (cave spider passes a 0.7 scale). */
public final class SpiderVisual extends SimpleLivingEntityVisual<EntitySpider, ModelSpider> {
    public SpiderVisual(VisualizationContext ctx, EntitySpider entity, float partialTick,
                        EntityModel<ModelSpider> model, Material body, Material eyes, String cacheKey,
                        float shadowRadius, float uniformScale) {
        super(ctx, entity, partialTick, model, body, cacheKey, shadowRadius, uniformScale, 180.0F);
        addLayer(new EmissiveLayer<>(ctx, instances, model, eyes, cacheKey + ":eyes", 1));
    }
}
