package dev.engine_room.flywheel.lib.visual;

import org.joml.Matrix4fc;

/**
 * A render layer on an {@link AbstractLivingEntityVisual} — the instanced analog of a vanilla
 * {@code LayerRenderer} (emissive eyes, held items, armor, dyed overlays).
 *
 * <p>The visual drives layers via {@link #beginFrame} each frame after the body is posed, passing the
 * body's root matrix; a layer typically mirrors the body's posed bones into its own instancers and adds
 * its own texture/material at a higher bias. Register layers from a subclass constructor with
 * {@link AbstractLivingEntityVisual#addLayer}.
 *
 * <p>{@code rootPose} is the ADULT root: on a baby, the body's roots carry per-root baby group transforms
 * that are not in this matrix. Pose-mirroring layers MUST therefore copy the body's composed matrices
 * ({@code InstanceTree.copyComposedFrom}) rather than recomposing locals under {@code rootPose}; layers
 * that consume {@code rootPose} directly are only baby-correct if vanilla skips them on babies anyway.
 *
 * <p>{@code bodyMoved} is true iff the body re-posed this frame (and on a reveal); a pose-mirroring layer may
 * early-return when it is false and its own state is unchanged.
 */
public interface LivingLayer {
    void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved);

    void setVisible(boolean visible);

    void delete();
}
