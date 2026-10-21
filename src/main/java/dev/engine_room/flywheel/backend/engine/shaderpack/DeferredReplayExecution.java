package dev.engine_room.flywheel.backend.engine.shaderpack;

final class DeferredReplayExecution implements AutoCloseable {
    private static DeferredReplayExecution current;
    final DeferredOitRenderer renderer;
    final DeferredReplayPass pass;
    final int layer;
    final boolean far;
    private final DeferredReplayExecution previous;

    DeferredReplayExecution(DeferredOitRenderer renderer, DeferredReplayPass pass, int layer, boolean far) {
        previous = current;
        this.renderer = renderer;
        this.pass = pass;
        this.layer = layer;
        this.far = far;
        current = this;
    }

    static boolean graphics(int program) {
        var context = current;
        if (context == null) return false;
        context.renderer.bindGraphics(context.pass, program, context.layer);
        if (!context.far) return false;
        context.renderer.draw(context.pass, context.layer);
        return true;
    }

    static boolean active() {
        return current != null;
    }

    static boolean compute(int program, int x, int y, int z) {
        var context = current;
        if (context == null) return false;
        return context.renderer.dispatch(context.pass, program, context.layer, context.far, x, y, z);
    }

    static boolean computeIndirect(int program, int buffer, long offset) {
        var context = current;
        if (context == null) return false;
        return context.renderer.dispatchIndirect(context.pass, program, context.layer, context.far, buffer, offset);
    }

    @Override
    public void close() {
        current = previous;
    }
}
