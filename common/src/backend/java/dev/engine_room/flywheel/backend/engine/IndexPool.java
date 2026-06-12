package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class IndexPool {
    private final Reference2IntMap<IndexSequence> indexCounts;
    private final Reference2IntMap<IndexSequence> firstIndices;
    @Nullable
    private GpuBuffer mojangEbo;
    private boolean dirty;

    public IndexPool() {
        indexCounts = new Reference2IntOpenHashMap<>();
        firstIndices = new Reference2IntOpenHashMap<>();

        indexCounts.defaultReturnValue(0);
    }

    public int firstIndex(IndexSequence sequence) {
        return firstIndices.getInt(sequence);
    }

    public void reset() {
        indexCounts.clear();
        firstIndices.clear();
        dirty = true;
    }

    public void updateCount(IndexSequence sequence, int indexCount) {
        int oldCount = indexCounts.getInt(sequence);
        int newCount = Math.max(oldCount, indexCount);

        if (newCount > oldCount) {
            indexCounts.put(sequence, newCount);
            dirty = true;
        }
    }

    public void flush() {
        if (!dirty) {
            return;
        }

        firstIndices.clear();
        dirty = false;

        long totalIndexCount = 0;

        for (int count : indexCounts.values()) {
            totalIndexCount += count;
        }

        final var indexBlock = MemoryBlock.malloc(totalIndexCount * Integer.BYTES);
        final long indexPtr = indexBlock.ptr();

        int firstIndex = 0;
        for (Reference2IntMap.Entry<IndexSequence> entries : indexCounts.reference2IntEntrySet()) {
            var indexSequence = entries.getKey();
            var indexCount = entries.getIntValue();

            firstIndices.put(indexSequence, firstIndex);

            indexSequence.fill(indexPtr + (long) firstIndex * Integer.BYTES, indexCount);

            firstIndex += indexCount;
        }

        if (mojangEbo != null) {
            // NOT close(): raw-VK index binds are invisible to Mojang's usage tracking; an immediate close
            // is a use-after-free under the in-flight frames (device loss). See BufferRetirement.
            BufferRetirement.retire(mojangEbo);
            mojangEbo = null;
        }
        if (totalIndexCount > 0) {
            ByteBuffer indexData = MemoryUtil.memByteBuffer(indexPtr, (int) (totalIndexCount * Integer.BYTES));
            mojangEbo = RenderSystem.getDevice()
                                    .createBuffer(() -> "flywheel index pool",
                                            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indexData);
        }

        indexBlock.free();
    }

    @Nullable
    public GpuBuffer indexBuffer() {
        return mojangEbo;
    }

    public void delete() {
        if (mojangEbo != null) {
            BufferRetirement.retire(mojangEbo);
            mojangEbo = null;
        }
    }
}
