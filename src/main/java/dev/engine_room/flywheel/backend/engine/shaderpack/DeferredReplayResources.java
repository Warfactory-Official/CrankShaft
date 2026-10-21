package dev.engine_room.flywheel.backend.engine.shaderpack;

interface DeferredReplayResources extends AutoCloseable {
    int width();

    int height();

    default boolean preservesViewport() {
        return false;
    }

    default int computeGroupSize() {
        return 0;
    }

    default void replayCommands(int buffer, long computeOffset) {
    }

    default void prepareCapture() {
    }

    void snapshot();

    default void observedCapture(int layers, int metadata) {
    }

    void materialize(int layer, int indexType, long drawOffset);

    void advance(int layer, int indexType, long drawOffset);

    default void beforePass(DeferredReplayPass pass, int layer) {
    }

    default void afterPass(DeferredReplayPass pass, int layer) {
    }

    default void bindGraphicsResources(DeferredReplayPass pass, int layer) {
    }

    default void bindComputeResources(DeferredReplayPass.Compute compute, int layer) {
    }

    void restore();

    default long allocatedBytes() {
        return 0;
    }

    default void release() {
    }

    @Override
    void close();
}
