package dev.engine_room.flywheel.iris.experimental;

/**
 * Render-thread scope owned by Iris color clears and consumed by the OpenGL pass-close hook.
 * The owner releases the scope and applies any deferred default-framebuffer bind on return or failure.
 * Validation errors before a pass closes leave the caller's bindings untouched.
 */
public final class GlClearSequence {
    public static boolean active;
    public static boolean pendingUnbind;

    private GlClearSequence() {
    }
}
