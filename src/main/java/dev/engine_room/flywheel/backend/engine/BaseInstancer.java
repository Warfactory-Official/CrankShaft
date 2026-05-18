package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.util.AtomicBitSet;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.*;

/**
 * Workers can't issue GL calls, so pages allocated during {@code Visual.frame()} use a
 * heap-calloc fallback. {@link #prepareUpload()} migrates them into the persistent-mapped
 * {@link SlabBuffer} on the render thread.
 */
public abstract class BaseInstancer<I extends Instance> extends AbstractInstancer<I> implements InstanceHandleImpl.State<I> {
    // Lock for all instances, only needs to be used in methods that may run on the TaskExecutor.
    protected final Object lock = new Object();
    protected final ArrayList<I> instances = new ArrayList<>();
    protected final ArrayList<InstanceHandleImpl<I>> handles = new ArrayList<>();

    protected final AtomicBitSet changed = new AtomicBitSet();
    protected final AtomicBitSet deleted = new AtomicBitSet();

    protected final long instanceStride;
    // Volatile so worker-thread setters see grow events without needing the lock.
    protected volatile long[] slabBlocks = new long[0];
    protected @Nullable SlabBuffer slabBuffer;
    // Set on worker threads at allocation time, cleared on the render thread after migration.
    protected final AtomicBitSet transientPages = new AtomicBitSet();

    protected BaseInstancer(InstancerKey<I> key, Recreate<I> recreate) {
        super(key, recreate);
        this.instanceStride = MoreMath.align16(type.layout().byteSize());
    }

    @Override
    public final long slabPtrAt(int index) {
        return slabBlocks[index >>> LOG_2_PAGE_SIZE] + (long) (index & PAGE_MASK) * instanceStride;
    }

    protected final void ensureSlabBlock(int index) {
        int pageIdx = index >>> LOG_2_PAGE_SIZE;
        long[] cur = slabBlocks;
        if (pageIdx < cur.length) {
            return;
        }
        long[] next = new long[pageIdx + 1];
        System.arraycopy(cur, 0, next, 0, cur.length);
        SlabBuffer buf = slabBuffer;
        long pageBytes = (long) PAGE_SIZE * instanceStride;
        for (int p = cur.length; p <= pageIdx; p++) {
            if (buf != null && p < buf.pageCapacity()) {
                next[p] = buf.ptrForPage(p);
            } else {
                next[p] = FlwMemoryTracker.calloc(PAGE_SIZE, instanceStride);
                FlwMemoryTracker._allocCpuMemory(pageBytes);
                transientPages.set(p);
            }
        }
        slabBlocks = next;
    }

    protected final @Nullable SlabBuffer prepareUpload() {
        long[] cur = slabBlocks;
        if (cur.length == 0) {
            return slabBuffer;
        }
        ensureSlabBufferInitialized();
        SlabBuffer buf = slabBuffer;
        // Recycle the buffer retired two frames ago; safe because GPU has long
        // since finished reading from it, and workers from the resize frame
        // are done.
        buf.releaseRetired();
        boolean grew = buf.ensureCapacity(cur.length);
        long pageBytes = (long) PAGE_SIZE * instanceStride;

        // If we grew, every page's existing slabBlocks entry points into the now-retired
        // mapped region. Migrate transient heap pages into the new region; rewrite the rest.
        long[] updated = grew ? cur.clone() : null;
        boolean anyTransient = !transientPages.isEmpty();
        if (grew || anyTransient) {
            for (int p = 0; p < cur.length; p++) {
                long mappedPtr = buf.ptrForPage(p);
                if (transientPages.get(p)) {
                    long heapPtr = cur[p];
                    MemoryUtil.memCopy(heapPtr, mappedPtr, pageBytes);
                    FlwMemoryTracker.free(heapPtr);
                    FlwMemoryTracker._freeCpuMemory(pageBytes);
                    transientPages.clear(p);
                    if (updated == null) {
                        updated = cur.clone();
                    }
                    updated[p] = mappedPtr;
                } else if (grew) {
                    updated[p] = mappedPtr;
                }
            }
            if (updated != null) {
                slabBlocks = updated;
            }
        }
        return buf;
    }

    private void ensureSlabBufferInitialized() {
        if (slabBuffer == null) {
            slabBuffer = new SlabBuffer((long) PAGE_SIZE * instanceStride, INITIAL_BUFFER_PAGES);
        }
    }

    @Override
    public InstanceHandleImpl.State<I> setChanged(int index) {
        notifyDirty(index);
        return this;
    }

    @Override
    public InstanceHandleImpl.State<I> setDeleted(int index) {
        notifyRemoval(index);
        return InstanceHandleImpl.Deleted.instance();
    }

    @Override
    public InstanceHandleImpl.State<I> setVisible(InstanceHandleImpl<I> handle, int index, boolean visible) {
        if (visible) {
            return this;
        }

        notifyRemoval(index);

        I instance;
        synchronized (lock) {
            instance = instances.get(index);
        }

        return new InstanceHandleImpl.Hidden<>(recreate, instance);
    }


    @Override
    public I createInstance() {
        var handle = new InstanceHandleImpl<>(this);
        I instance = type.create(handle);

        synchronized (lock) {
            handle.index = instances.size();
            addLocked(instance, handle);
            return instance;
        }
    }

    @Override
    public InstanceHandleImpl.State<I> revealInstance(InstanceHandleImpl<I> handle, I instance) {
        synchronized (lock) {
            handle.index = instances.size();
            addLocked(instance, handle);
        }
        return this;
    }

    @Override
    public void stealInstance(@Nullable I instance) {
        if (instance == null) {
            return;
        }

        var instanceHandle = instance.handle();

        if (!(instanceHandle instanceof InstanceHandleImpl<?>)) {
            return;
        }

        @SuppressWarnings("unchecked") var handle = (InstanceHandleImpl<I>) instanceHandle;

        if (handle.state == this) {
            return;
        }
        if (handle.state instanceof InstanceHandleImpl.Deleted) {
            return;
        }
        if (handle.state instanceof InstanceHandleImpl.Hidden<I> hidden && recreate.equals(hidden.recreate())) {
            return;
        }

        if (handle.state instanceof BaseInstancer<I> other) {
            other.notifyRemoval(handle.index);

            handle.state = this;
            synchronized (lock) {
                handle.index = instances.size();
                addLocked(instance, handle);
            }
        } else if (handle.state instanceof InstanceHandleImpl.Hidden<I>) {
            handle.state = new InstanceHandleImpl.Hidden<>(recreate, instance);
        }
    }

    /**
     * Calls must be synchronized on {@link #lock}.
     */
    private void addLocked(I instance, InstanceHandleImpl<I> handle) {
        instances.add(instance);
        handles.add(handle);
        ensureSlabBlock(handle.index);
        type.seed().accept(slabPtrAt(handle.index));
        setIndexChanged(handle.index);
    }

    @Override
    public int instanceCount() {
        return instances.size();
    }

    public void notifyDirty(int index) {
        if (index < 0 || index >= instanceCount()) {
            return;
        }
        setIndexChanged(index);
    }

    protected void setIndexChanged(int index) {
        changed.set(index);
    }

    public void notifyRemoval(int index) {
        if (index < 0 || index >= instanceCount()) {
            return;
        }
        deleted.set(index);
    }

    /**
     * Clear all instances without freeing GL resources. Safe to call from any thread —
     * matches upstream's BaseInstancer.clear() contract. Transient heap pages are freed
     * here (nmemFree is thread-safe); GL teardown lives in {@link #freeGlResources()}.
     */
    @Override
    public void clear() {
        long[] blocks = slabBlocks;
        long pageBytes = (long) PAGE_SIZE * instanceStride;
        for (int p = 0; p < blocks.length; p++) {
            if (transientPages.get(p)) {
                FlwMemoryTracker.free(blocks[p]);
                FlwMemoryTracker._freeCpuMemory(pageBytes);
            }
        }
        transientPages.clear();
        slabBlocks = new long[0];
        for (InstanceHandleImpl<I> handle : handles) {
            if (handle.state == this) {
                handle.clear();
                handle.state = InstanceHandleImpl.Deleted.instance();
            }
        }
        instances.clear();
        handles.clear();
        changed.clear();
        deleted.clear();
    }

    /** Release the per-instancer slab GL buffer. Render-thread only. */
    protected void freeGlResources() {
        if (slabBuffer != null) {
            slabBuffer.delete();
            slabBuffer = null;
        }
    }
}
