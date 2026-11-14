package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import net.irisshaders.iris.compat.dh.DHCompat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * A native-depth pack's DH projections, built reversed like vanilla's level projection.
 */
public final class NativeDhProjection {
    private NativeDhProjection() {
    }

    /**
     * {@code dhProjection}: DH's own planes, or the level projection itself without DH.
     */
    public static Matrix4fc uniform() {
        return DistantHorizonsCompat.ACTIVE ? of(DHCompat.getNearPlane(), DHCompat.getFarPlane())
                : NativeDepth.levelProjection();
    }

    public static Matrix4f of(float near, float far) {
        Matrix4fc level = NativeDepth.levelProjection();
        return new Matrix4f().setPerspective(level.perspectiveFov(), level.m11() / level.m00(), far, near,
                RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }
}
