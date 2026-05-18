package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.backend.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

public final class CameraImpl implements Camera {
    private final Entity entity;
    private final Vec3d position;
    private final Vec3d eyePosition;
    private final float xRot;
    private final float yRot;

    public CameraImpl(Entity entity, float partialTick, Vec3d position, Vec3d eyePosition) {
        this.entity = entity;
        this.position = position;
        this.eyePosition = eyePosition;
        this.xRot = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTick;
        this.yRot = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTick;
    }

    @Override
    public Vec3d getPosition() {
        return position;
    }

    @Override
    public Vec3d eyePosition() {
        return eyePosition;
    }

    @Override
    public Vector3f getLookVector() {
        Vec3d look = entity.getLook(1.0f);
        return new Vector3f((float) look.x, (float) look.y, (float) look.z);
    }

    @Override
    public float getXRot() {
        return xRot;
    }

    @Override
    public float getYRot() {
        return yRot;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }

    @Override
    public BlockPos getBlockPosition() {
        return new BlockPos(position);
    }
}
