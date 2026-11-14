package dev.engine_room.flywheel.backend.engine.indirect;

import org.joml.Matrix4fc;

/**
 * Borrowed GL attachments and camera projection for one transparency resolve. The caller keeps them alive until
 * every producer and the resolve finish. Depth contains opaque geometry when production begins.
 * {@code depthAttachment} is GL_DEPTH_ATTACHMENT or GL_DEPTH_STENCIL_ATTACHMENT; stencil -1 disables masking.
 */
public record RawOitTarget(int color, int depth, int width, int height, Matrix4fc projection,
                           boolean zeroToOne, boolean reverseZ, int depthAttachment, int stencil) {
}
