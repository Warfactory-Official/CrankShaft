package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import net.minecraft.client.model.ModelSkeleton;
import net.minecraft.entity.monster.EntityStray;

/** Stray icy clothing overlay (vanilla {@code LayerStrayClothing}); copies the body pose one-to-one. */
public final class StrayClothingLayer extends CopyPoseLayer {
    public StrayClothingLayer(VisualizationContext ctx, EntityStray stray, InstanceTree body,
                              EntityModel<ModelSkeleton> model, Material material, String cacheKey, int bias) {
        super(ctx, stray, body, AbstractLivingEntityVisual.buildTree(model, material, cacheKey), bias);
    }

    @Override
    protected int color(float partialTick) {
        return 0xFFFFFFFF;
    }
}
