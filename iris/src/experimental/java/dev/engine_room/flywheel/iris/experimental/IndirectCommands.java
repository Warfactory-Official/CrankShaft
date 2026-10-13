package dev.engine_room.flywheel.iris.experimental;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

/**
 * Batched terrain on an open Iris pass; produce count and commands before opening it.
 */
public interface IndirectCommands {
    void flywheel$drawIndexedIndirect(GpuBufferSlice commands, int count, int stride);

    void flywheel$drawIndexedIndirectCount(GpuBufferSlice commands, GpuBufferSlice count, int maximum);
}
