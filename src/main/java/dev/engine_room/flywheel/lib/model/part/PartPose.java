package dev.engine_room.flywheel.lib.model.part;

import dev.engine_room.flywheel.lib.model.baked.ModelBaseConverter;
import net.minecraft.client.model.ModelRenderer;

/**
 * 1.12.2: stands in for {@code net.minecraft.client.model.geom.PartPose} (1.17+). Position is in
 * world units (multiply by {@link dev.engine_room.flywheel.lib.model.baked.ModelBaseConverter#DEFAULT_SCALE}
 * = 0.0625 when reading from a {@code ModelRenderer}). Rotations are radians, ZYX order applied
 * via {@code Matrix4f}.
 */
public record PartPose(float x, float y, float z, float xRot, float yRot, float zRot) {
    public static final PartPose ZERO = new PartPose(0F, 0F, 0F, 0F, 0F, 0F);

    public static PartPose offsetAndRotation(float x, float y, float z, float xRot, float yRot, float zRot) {
        return new PartPose(x, y, z, xRot, yRot, zRot);
    }

    public static PartPose offset(float x, float y, float z) {
        return new PartPose(x, y, z, 0F, 0F, 0F);
    }

    public static PartPose rotation(float xRot, float yRot, float zRot) {
        return new PartPose(0F, 0F, 0F, xRot, yRot, zRot);
    }

    public static PartPose fromRenderer(ModelRenderer r) {
        return new PartPose(
                r.rotationPointX * ModelBaseConverter.DEFAULT_SCALE,
                r.rotationPointY * ModelBaseConverter.DEFAULT_SCALE,
                r.rotationPointZ * ModelBaseConverter.DEFAULT_SCALE,
                r.rotateAngleX, r.rotateAngleY, r.rotateAngleZ);
    }
}
