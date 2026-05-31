package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.BatEntityModel;
import net.minecraft.client.model.ModelBat;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

public final class BatVisual extends AbstractLivingEntityVisual<EntityBat, ModelBat> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/bat.png");

    public BatVisual(VisualizationContext ctx, EntityBat entity, float partialTick) {
        super(ctx, entity, partialTick, new BatEntityModel(), MATERIAL, "vanillin:bat", 0.25F);
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(0.35F);
    }

    @Override
    protected void applyRotations(Matrix4f dest, float bodyYaw, float partialTick) {
        float dy = entity.getIsBatHanging() ? -0.1F
                : MathHelper.cos(handleRotationFloat(partialTick) * 0.3F) * 0.1F;
        dest.translate(0.0F, dy, 0.0F);
        super.applyRotations(dest, bodyYaw, partialTick);
    }
}
