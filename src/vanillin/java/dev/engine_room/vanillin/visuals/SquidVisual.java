package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.SquidEntityModel;
import net.minecraft.client.model.ModelSquid;
import net.minecraft.entity.passive.EntitySquid;
import org.joml.Matrix4f;

/** Squid — body plus tentacles. {@code RenderSquid} fully replaces the body-yaw transform with a
 *  swim pitch/yaw orientation and floats the model; there is no death flop. The tentacle wave reads the
 *  interpolated {@code tentacleAngle} through the {@code setRotationAngles} age argument. */
public final class SquidVisual extends AbstractLivingEntityVisual<EntitySquid, ModelSquid> {
    private static final Material MATERIAL = EntityMaterials.living("textures/entity/squid.png");

    public SquidVisual(VisualizationContext ctx, EntitySquid entity, float partialTick) {
        super(ctx, entity, partialTick, new SquidEntityModel(), MATERIAL, "vanillin:squid", 0.7F);
    }

    @Override
    protected void applyRotations(Matrix4f dest, float bodyYaw, float partialTick) {
        float pitch = entity.prevSquidPitch + (entity.squidPitch - entity.prevSquidPitch) * partialTick;
        float yaw = entity.prevSquidYaw + (entity.squidYaw - entity.prevSquidYaw) * partialTick;
        dest.translate(0.0F, 0.5F, 0.0F);
        dest.rotateY((float) Math.toRadians(180.0F - bodyYaw));
        dest.rotateX((float) Math.toRadians(pitch));
        dest.rotateY((float) Math.toRadians(yaw));
        dest.translate(0.0F, -1.2F, 0.0F);
    }

    @Override
    protected float handleRotationFloat(float partialTick) {
        return entity.lastTentacleAngle + (entity.tentacleAngle - entity.lastTentacleAngle) * partialTick;
    }
}
