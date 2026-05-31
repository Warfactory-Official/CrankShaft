package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.EntityModel;
import net.minecraft.client.model.ModelSheep1;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.item.EnumDyeColor;

/**
 * Sheep wool: {@code ModelSheep1} (inflated fleece) re-instanced over the bare {@code ModelSheep2} body,
 * tinted per-instance by the fleece dye colour and hidden when the sheep is sheared. {@code ModelSheep1}
 * and {@code ModelSheep2} share bone transforms (same {@code ModelQuadruped} base + identical grazing
 * override), so the wool copies the body's posed bones one-to-one rather than reposing a second model.
 */
public final class SheepWoolLayer extends CopyPoseLayer {
    private static final int DYE_COLORS = EnumDyeColor.values().length;

    private final EntitySheep sheep;

    public SheepWoolLayer(VisualizationContext ctx, EntitySheep sheep, InstanceTree body,
                          EntityModel<ModelSheep1> model, Material material, String cacheKey, int bias) {
        super(ctx, sheep, body, AbstractLivingEntityVisual.buildTree(model, material, cacheKey), bias);
        this.sheep = sheep;
    }

    @Override
    protected boolean show() {
        return !sheep.getSheared();
    }

    @Override
    protected int color(float partialTick) {
        float r;
        float g;
        float b;
        if (sheep.hasCustomName() && "jeb_".equals(sheep.getCustomNameTag())) {
            int idx = sheep.ticksExisted / 25 + sheep.getEntityId();
            float f = (sheep.ticksExisted % 25 + partialTick) / 25.0F;
            float[] c0 = EntitySheep.getDyeRgb(EnumDyeColor.byMetadata(idx % DYE_COLORS));
            float[] c1 = EntitySheep.getDyeRgb(EnumDyeColor.byMetadata((idx + 1) % DYE_COLORS));
            r = c0[0] * (1.0F - f) + c1[0] * f;
            g = c0[1] * (1.0F - f) + c1[1] * f;
            b = c0[2] * (1.0F - f) + c1[2] * f;
        } else {
            float[] c = EntitySheep.getDyeRgb(sheep.getFleeceColor());
            r = c[0];
            g = c[1];
            b = c[2];
        }
        return 0xFF000000
                | (Math.round(r * 255.0F) << 16)
                | (Math.round(g * 255.0F) << 8)
                | Math.round(b * 255.0F);
    }
}
