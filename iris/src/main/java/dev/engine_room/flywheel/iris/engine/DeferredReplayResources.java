package dev.engine_room.flywheel.iris.engine;

import net.irisshaders.iris.gl.program.ComputeProgram;

/**
 * Pack-specific materialization, scratch images and history ownership for a deferred replay plan. All calls occur
 * on the render thread; resources live until the owning Iris CompositeRenderer is destroyed. Width/height must
 * describe the capture's pixel grid without allocating storage. Snapshot allocates lazily and preserves the
 * native frame's inputs; release drops size-dependent storage after an idle interval without closing programs.
 *
 * <p>The replayer visits far layers back-to-front, then lets Iris execute the nearest layer normally. The indirect
 * draw buffer and fullscreen indices are bound for materialization/advance; their byte offset is supplied rather
 * than implied by a node or command layout. Callbacks preserve those bindings and the scheduler's stencil state.
 * Pass hooks cover both replayed
 * far layers and native nearest-layer stages, allowing integrations to isolate custom images and temporal writes.
 * Restore is called at the declared exit, at renderer completion, or while unwinding a replay failure. CPU hooks
 * may visit inactive layers within the asynchronous loop bound; shader/history writes must honor GPU layer
 * activity. Resource-binding hooks run after Iris binds the program and custom uniforms, before its execution.
 */
public interface DeferredReplayResources extends AutoCloseable {
    int width();

    int height();

    /**
     * Whether callbacks leave the viewport untouched, allowing the scheduler to coalesce identical viewport changes.
     */
    default boolean preservesViewport() {
        return false;
    }

    /**
     * Square local size for resource compute shaders on the capture grid, or zero when none are used.
     */
    default int computeGroupSize() {
        return 0;
    }

    /**
     * Supplies the scheduler-owned indirect buffer and a resource dispatch's byte offset relative to each draw
     * command. Called on the render thread before materialization when computeGroupSize is nonzero. Far-layer
     * callbacks may dispatch from the bound buffer; nearest-pass callbacks must bind and restore it themselves.
     */
    default void replayCommands(int buffer, long computeOffset) {
    }

    void snapshot();

    /**
     * Most recently completed capture; metadata is the adapter-owned fourth counter word.
     */
    default void observedCapture(int layers, int metadata) {
    }

    void materialize(int layer, int indexType, long drawOffset);

    void advance(int layer, int indexType, long drawOffset);

    default void beforePass(DeferredCompositePass pass, int layer) {
    }

    default void afterPass(DeferredCompositePass pass, int layer) {
    }

    default void bindComputeResources(ComputeProgram program, int layer) {
    }

    default void bindGraphicsResources(DeferredCompositePass pass, int layer) {
    }

    void restore();

    void release();

    long allocatedBytes();

    @Override
    void close();
}
