package dev.engine_room.flywheel.backend.engine.far;

import org.joml.Matrix4fc;

/**
 * A host's completed opaque terrain and vanilla depth, before retained visuals and translucent terrain draw.
 * Vanilla depth is zero when vanilla opaque terrain follows this host stage. Matrices map the current camera's
 * view space to each depth image. The vanilla scale follows the host's depth
 * mask lookup when its viewport differs from the source image. Consumed synchronously on the render thread.
 */
public record FarDepth(int terrainDepth, int vanillaDepth, int width, int height,
                       Matrix4fc terrainProjection, Matrix4fc vanillaProjection,
                       boolean terrainZeroToOne, boolean terrainReverseZ,
                       boolean vanillaZeroToOne, boolean vanillaReverseZ,
                       float vanillaUScale, float vanillaVScale) {
}
