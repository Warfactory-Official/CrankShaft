package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.joml.Matrix4f;

public final class FireworkVisual extends ThrownItemVisual<FireworkRocketEntity> {
    public FireworkVisual(VisualizationContext ctx, FireworkRocketEntity entity, float partialTick) {
        super(ctx, entity, partialTick, 1.0F, false);
    }

    @Override
    protected void orient(Matrix4f pose) {
        if (entity.isShotAtAngle()) {
            pose.rotateZ((float) Math.PI)
                .rotateY((float) Math.PI)
                .rotateX((float) (Math.PI / 2));
        }
    }
}
