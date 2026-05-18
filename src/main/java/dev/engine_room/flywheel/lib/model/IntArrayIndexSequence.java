package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.IndexSequence;
import org.lwjgl.system.MemoryUtil;

public final class IntArrayIndexSequence implements IndexSequence {
    private final int[] indices;

    public IntArrayIndexSequence(int[] indices) {
        this.indices = indices;
    }

    @Override
    public void fill(long ptr, int count) {
        int n = Math.min(count, indices.length);
        for (int i = 0; i < n; i++) {
            MemoryUtil.memPutInt(ptr + (long) i * 4L, indices[i]);
        }
    }
}
