package dev.engine_room.flywheel.impl.far;

import org.joml.Matrix4fc;

/**
 * This frame's retained opaque surfaces for a shaderpack's LoD program. Colour is unshaded albedo with coverage;
 * normal is the world normal encoded into RGB and native diffuse in alpha; light holds block/sky lightmap
 * coordinates and the unlit fraction. The integer material target holds original UV float bits in xy,
 * texture slot and material id in z (16 bits each), and a signed-byte world tangent in w's low three bytes.
 * Its upper byte holds negative handedness in bit 0 and the LoD material category in bits 1-4.
 * Depth uses the far projection's zero-to-one reversed Z.
 * Matrices reconstruct view space from depth, then camera-relative world space. Texture names and matrices remain
 * owned by {@link FarVisuals}; consumers draw on the render thread during the same frame.
 */
public record FarSurface(int color, int normal, int light, int material, int depth, int width, int height,
                         Matrix4fc inverseProjection, Matrix4fc viewToWorld) {
}
