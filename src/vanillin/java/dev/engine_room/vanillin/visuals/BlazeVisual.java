package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.BlazeEntityModel;
import net.minecraft.client.model.ModelBlaze;
import net.minecraft.entity.monster.EntityBlaze;

public final class BlazeVisual extends AbstractLivingEntityVisual<EntityBlaze, ModelBlaze> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/blaze.png");

    public BlazeVisual(VisualizationContext ctx, EntityBlaze entity, float partialTick) {
        super(ctx, entity, partialTick, new BlazeEntityModel(), MATERIAL, "vanillin:blaze", 0.5F);
    }

    // EntityBlaze.getBrightnessForRender is fullbright.
    @Override
    protected int computePackedLight(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }
}
