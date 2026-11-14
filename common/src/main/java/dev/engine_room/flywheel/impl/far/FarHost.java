package dev.engine_room.flywheel.impl.far;

import org.joml.Matrix4fc;

/**
 * A LoD renderer's opaque GL targets and depth convention, mid-frame.
 *
 * @param viewToHostClip camera view space (the main model view's) to the host's clip space
 * @param stencil        the stencil value of pixels the host may draw over, or -1 for no stencil test
 */
public record FarHost(int color, int depth, int width, int height, Matrix4fc viewToHostClip, boolean zeroToOne,
                      boolean reverseZ, int stencil) {
}
