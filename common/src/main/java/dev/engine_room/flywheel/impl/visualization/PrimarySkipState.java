package dev.engine_room.flywheel.impl.visualization;

/**
 * Mixed into {@code EntityRenderState}: set at {@code EntityRenderDispatcher.extractEntity}, read by the renderer
 * hooks that drop only the primary submit.
 */
public interface PrimarySkipState {
    boolean flywheel$skipPrimary();

    void flywheel$setSkipPrimary(boolean skip);
}
