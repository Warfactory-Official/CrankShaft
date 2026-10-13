package dev.engine_room.flywheel.iris.experimental;

import net.irisshaders.iris.shaderpack.FilledIndirectPointer;

/**
 * Render-thread dispatch of a borrowed pack compute through the replay GPU gate.
 */
public interface ComputeIndirectDispatch {
    void flywheel$dispatchIndirect(FilledIndirectPointer command, long tick, long frame);
}
