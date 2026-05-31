package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import net.minecraft.client.model.ModelWolf;
import net.minecraft.entity.passive.EntityWolf;

/** Tamed wolf's dyed collar (vanilla {@code LayerWolfCollar}); posed off the body and tinted by collar dye. */
public final class WolfCollarLayer extends CopyPoseLayer {
    private final EntityWolf wolf;

    public WolfCollarLayer(VisualizationContext ctx, EntityWolf wolf, InstanceTree body,
                           EntityModel<ModelWolf> model, Material material, String cacheKey, int bias) {
        super(ctx, wolf, body, AbstractLivingEntityVisual.buildTree(model, material, cacheKey), bias);
        this.wolf = wolf;
    }

    @Override
    protected boolean show() {
        return wolf.isTamed();
    }

    @Override
    protected int color(float partialTick) {
        float[] c = wolf.getCollarColor().getColorComponentValues();
        return 0xFF000000
                | (Math.round(c[0] * 255.0F) << 16)
                | (Math.round(c[1] * 255.0F) << 8)
                | Math.round(c[2] * 255.0F);
    }
}
