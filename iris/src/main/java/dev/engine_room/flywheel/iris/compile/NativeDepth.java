package dev.engine_room.flywheel.iris.compile;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;

/**
 * Whether the current pack takes Minecraft's raw reversed depth and projections
 * ({@link ContractShaderPack#flywheel$nativeDepth}). Iris runs one pack at a time and rebuilds every pipeline on a
 * pack change, so a per-pack answer is a per-pipeline one.
 */
public final class NativeDepth {
    private NativeDepth() {
    }

    public static boolean active() {
        return Iris.getCurrentPack().map(ShaderPack::flywheel$nativeDepth).orElse(false);
    }

    /**
     * The level projection as written to the projection buffer, before Iris's {@code undoRevZ}.
     */
    public static Matrix4fc levelProjection() {
        return Minecraft.getInstance().gameRenderer.sodium$getProjectionMatrix();
    }

    /**
     * Mixed into Iris's transform cache key, so the two depth contracts never share an entry.
     */
    public interface Keyed {
        boolean flywheel$nativeDepth();
    }
}
