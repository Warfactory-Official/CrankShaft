package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.visual.HeldItemLayer;
import dev.engine_room.flywheel.lib.visual.SimpleLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.VexEntityModel;
import net.minecraft.client.model.ModelVex;
import net.minecraft.entity.monster.EntityVex;
import org.joml.Matrix4f;

/** Vex — biped-derived, scaled 0.4, fullbright ({@code EntityVex.getBrightnessForRender} returns {@code FULL_BRIGHT}). */
public final class VexVisual extends SimpleLivingEntityVisual<EntityVex, ModelVex> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/illager/vex.png");

    public VexVisual(VisualizationContext ctx, EntityVex entity, float partialTick) {
        super(ctx, entity, partialTick, new VexEntityModel(), MATERIAL, "vex", 0.3F, 0.4F, 90.0F);
        // 2/3 = right/left arm roots in VexEntityModel.
        addLayer(new HeldItemLayer(ctx, entity, instances, 2, 3, 1));
    }

    @Override
    protected int computePackedLight(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    protected void applyModelTransform(Matrix4f dest) {
        // ModelVex.render runs ModelBiped.render via super, inheriting its sneak drop.
        if (entity.isSneaking()) {
            dest.translate(0.0F, 0.2F, 0.0F);
        }
    }
}
