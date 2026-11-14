package dev.engine_room.flywheel.api.instance;

import dev.engine_room.flywheel.api.backend.BackendImplemented;

import java.util.function.LongConsumer;

/**
 * A persistent GPU simulation and its rendered population. Its simulation state survives render-origin changes.
 */
@BackendImplemented
public interface GpuPopulation {
    /**
     * Establish the completed simulation tick imported by the initialize shader. Call before the backend first
     * prepares this population; populations without imported state start at tick zero.
     */
    void initialize(int tick);

    /**
     * Supply the parameter block and the simulation tick to advance toward. Call during visual updates,
     * before the engine prepares the frame. The writer receives the native address of the parameter block.
     */
    void parameters(int targetTick, LongConsumer writer);

    /**
     * Supply a range of the auxiliary block during visual updates, before the engine prepares the frame.
     * The writer receives the native address of the start of the range and writes {@code bytes} bytes.
     */
    void auxiliary(int offset, int bytes, LongConsumer writer);

    /**
     * Receive the final simulation state before backend teardown invalidates this population. The reader receives
     * native addresses of the state, control, auxiliary and sorted-order blocks. Those addresses remain valid only
     * during the callback. Explicit {@link #delete()} does not invoke the reader.
     */
    void onInvalidate(SnapshotReader reader);

    /**
     * Whether this population has been deleted.
     */
    boolean deleted();

    /**
     * Retire the population and release its simulation and rendering resources.
     */
    void delete();

    @FunctionalInterface
    interface SnapshotReader {
        void read(long state, long control, long auxiliary, long order);
    }
}
