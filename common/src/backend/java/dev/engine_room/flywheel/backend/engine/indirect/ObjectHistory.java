package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.engine.Slab;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The GL indirect object buffer as the previous frame drew it, at the same object offsets: shaderpack motion vectors
 * run each moved instance's vertex shader over both. Before this frame's uploads, the ranges written last frame are
 * copied over (any range copied then still holds last frame's data, so runs merge across short gaps); instances new
 * at their offset are then uploaded here too, so they carry no motion of their own. A bit per object uint marks the
 * first uint of each instance changed since last frame; only pages moved last frame or this frame are rewritten.
 * Render thread only.
 */
final class ObjectHistory implements IndirectInstancer.History {
    private static final long MERGE_GAP = 4096;
    // Object offset << SIZE_BITS | size: a page's live range is below 2^SIZE_BITS bytes (strideUints < 2^16).
    private static final int SIZE_BITS = 24;
    private static final long SIZE_MASK = (1L << SIZE_BITS) - 1;

    private final GlSlabArena arena;
    private @Nullable ResizableStorageBuffer buffer;
    private @Nullable ResizableStorageBuffer moved;
    private long[] written = new long[64];
    private int writtenCount;
    // Pages are 32 instances of whole uints, so each starts on a bit word: (word, strideUints, mask) this frame,
    // (word, words) last frame.
    private final IntArrayList movedPages = new IntArrayList();
    private final IntArrayList lastMovedPages = new IntArrayList();
    private final IntOpenHashSet rewritten = new IntOpenHashSet();
    private @Nullable MemoryBlock scratch;

    ObjectHistory(GlSlabArena arena) {
        this.arena = arena;
    }

    /**
     * Before any of this frame's object uploads; {@code false} (and nothing retained) unless {@code keep}.
     */
    boolean begin(boolean keep, ObjectStorage storage) {
        if (!keep) {
            delete();
            return false;
        }
        ResizableStorageBuffer objects = storage.objectBuffer;
        boolean whole = buffer == null;
        if (whole) {
            buffer = new ResizableStorageBuffer();
            moved = new ResizableStorageBuffer();
        }
        if (buffer.capacity() < objects.capacity()) {
            buffer.ensureCapacity(objects.capacity());
        }
        long bitBytes = (objects.capacity() + 127) / 128 * Integer.BYTES;
        if (moved.capacity() < bitBytes) {
            long cleared = moved.capacity();
            moved.ensureCapacity(bitBytes);
            GL45C.glClearNamedBufferSubData(moved.handle(), GL30C.GL_R32UI, cleared, bitBytes - cleared,
                    GL30C.GL_RED_INTEGER, GL30C.GL_UNSIGNED_INT, (ByteBuffer) null);
        }
        if (whole) {
            copy(objects, 0, storage.bytesUsed());
        } else {
            Arrays.sort(written, 0, writtenCount);
            for (int i = 0; i < writtenCount; ) {
                long start = written[i] >>> SIZE_BITS;
                long end = start + (written[i] & SIZE_MASK);
                for (i++; i < writtenCount && (written[i] >>> SIZE_BITS) <= end + MERGE_GAP; i++) {
                    end = Math.max(end, (written[i] >>> SIZE_BITS) + (written[i] & SIZE_MASK));
                }
                copy(objects, start, end - start);
            }
        }
        writtenCount = 0;
        movedPages.clear();
        return true;
    }

    void record(long offset, long size) {
        if (writtenCount == written.length) {
            written = Arrays.copyOf(written, writtenCount * 2);
        }
        written[writtenCount++] = offset << SIZE_BITS | size;
    }

    @Override
    public void fresh(Slab slab, long srcByteOffset, long dstByteOffset, long byteSize) {
        arena.enqueue(slab, srcByteOffset, dstByteOffset, byteSize, buffer.handle());
    }

    @Override
    public void moved(long pageByteOffset, int strideUints, int mask) {
        movedPages.add((int) (pageByteOffset >>> 7));
        movedPages.add(strideUints);
        movedPages.add(mask);
    }

    /**
     * After this frame's object uploads, before {@code staging} flushes: the moved bits of every page moved last
     * frame or this frame.
     */
    void uploadMoved(StagingBuffer staging) {
        rewritten.clear();
        for (int i = 0; i < movedPages.size(); i += 3) {
            rewritten.add(movedPages.getInt(i));
        }
        for (int i = 0; i < lastMovedPages.size(); i += 2) {
            int word = lastMovedPages.getInt(i);
            if (!rewritten.contains(word)) {
                long bytes = (long) lastMovedPages.getInt(i + 1) * Integer.BYTES;
                long ptr = scratch(bytes);
                MemoryUtil.memSet(ptr, 0, bytes);
                staging.enqueueCopy(ptr, bytes, moved.handle(), (long) word * Integer.BYTES);
            }
        }
        lastMovedPages.clear();
        for (int i = 0; i < movedPages.size(); i += 3) {
            int word = movedPages.getInt(i);
            int strideUints = movedPages.getInt(i + 1);
            int mask = movedPages.getInt(i + 2);
            long bytes = (long) strideUints * Integer.BYTES;
            long ptr = scratch(bytes);
            MemoryUtil.memSet(ptr, 0, bytes);
            for (int bits = mask; bits != 0; bits &= bits - 1) {
                int uint = Integer.numberOfTrailingZeros(bits) * strideUints;
                long at = ptr + (long) (uint >>> 5) * Integer.BYTES;
                MemoryUtil.memPutInt(at, MemoryUtil.memGetInt(at) | 1 << (uint & 31));
            }
            staging.enqueueCopy(ptr, bytes, moved.handle(), (long) word * Integer.BYTES);
            lastMovedPages.add(word);
            lastMovedPages.add(strideUints);
        }
    }

    int handle() {
        return buffer.handle();
    }

    /**
     * At {@link BufferBindings#HISTORY_INSTANCE} and {@link BufferBindings#HISTORY_MOVED}; this frame's objects for
     * both when no history is kept (either path then sees no motion of its own).
     */
    void bind(ResizableStorageBuffer objects) {
        ResizableStorageBuffer history = buffer == null ? objects : buffer;
        ResizableStorageBuffer bits = moved == null ? objects : moved;
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.HISTORY_INSTANCE, history.handle(), 0,
                history.capacity());
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.HISTORY_MOVED, bits.handle(), 0,
                bits.capacity());
    }

    void delete() {
        if (buffer != null) {
            buffer.delete();
            buffer = null;
            moved.delete();
            moved = null;
        }
        if (scratch != null) {
            scratch.free();
            scratch = null;
        }
        writtenCount = 0;
        movedPages.clear();
        lastMovedPages.clear();
    }

    private long scratch(long bytes) {
        if (scratch == null) {
            scratch = MemoryBlock.malloc(Math.max(bytes, 1024));
        } else if (scratch.size() < bytes) {
            scratch = scratch.realloc(bytes);
        }
        return scratch.ptr();
    }

    private void copy(ResizableStorageBuffer objects, long offset, long size) {
        if (size > 0) {
            GL45C.glCopyNamedBufferSubData(objects.handle(), buffer.handle(), offset, offset, size);
        }
    }
}
