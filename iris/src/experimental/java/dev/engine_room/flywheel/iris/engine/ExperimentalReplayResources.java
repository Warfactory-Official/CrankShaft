package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

/**
 * Render-thread snapshot and materialization ownership for far-to-near replay.
 */
interface ExperimentalReplayResources extends AutoCloseable {
    void prepareCapture();

    void snapshot(GpuBuffer indices, GpuBufferSlice command);

    void materialize(int layer, GpuBuffer indices, GpuBufferSlice command);

    void advance(int layer, GpuBuffer indices, GpuBufferSlice command);

    void restore();

    @Override
    void close();
}
