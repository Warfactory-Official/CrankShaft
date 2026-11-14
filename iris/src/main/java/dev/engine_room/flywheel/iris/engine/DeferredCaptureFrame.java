package dev.engine_room.flywheel.iris.engine;

/**
 * Implemented by the pack's composite renderer and invoked on the render thread after native resize/setup,
 * before begin passes clear capture storage. The boundary publishes pending node-pool changes after all
 * previous-frame consumers and diagnostics, before the new main-world capture.
 */
public interface DeferredCaptureFrame {
    void flywheel$beginDeferredCapture();
}
