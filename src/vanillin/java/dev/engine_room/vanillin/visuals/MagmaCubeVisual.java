package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.MagmaCubeEntityModel;
import net.minecraft.client.model.ModelMagmaCube;
import net.minecraft.entity.monster.EntityMagmaCube;
import org.joml.Matrix4f;

public final class MagmaCubeVisual extends AbstractLivingEntityVisual<EntityMagmaCube, ModelMagmaCube> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/slime/magmacube.png");

    public MagmaCubeVisual(VisualizationContext ctx, EntityMagmaCube entity, float partialTick) {
        super(ctx, entity, partialTick, new MagmaCubeEntityModel(), MATERIAL, "vanillin:magmacube", 0.25F);
    }

    // RenderMagmaCube.preRenderCallback: size + squish (x/z by f1, y by 1/f1). No leading 0.999 shrink (that's slime-only).
    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        int size = entity.getSlimeSize();
        float f = (entity.prevSquishFactor + (entity.squishFactor - entity.prevSquishFactor) * partialTick) / (size * 0.5F + 1.0F);
        float f1 = 1.0F / (f + 1.0F);
        dest.scale(f1 * size, (1.0F / f1) * size, f1 * size);
    }

    // EntityMagmaCube.getBrightnessForRender is fullbright.
    @Override
    protected int computePackedLight(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }
}
