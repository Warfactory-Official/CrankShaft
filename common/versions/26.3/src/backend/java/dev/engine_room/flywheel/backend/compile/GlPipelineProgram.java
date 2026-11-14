package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.backend.opengl.GlProgram;

/**
 * A generated pipeline's GL program ABI, including any explicitly lowered push constants.
 */
public interface GlPipelineProgram {
    boolean supportsGlPushConstants();

    GlProgram createGlProgram(int handle);
}
