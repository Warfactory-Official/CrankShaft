package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.util.AtomicBitSet;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.*;

public abstract class BaseInstancer<I extends Instance> extends AbstractInstancer<I> implements InstanceHandleImpl.State<I> {
    // Lock for all instances, only needs to be used in methods that may run on the TaskExecutor.
    protected final Object lock = new Object();
    protected final ArrayList<I> instances = new ArrayList<>();
    protected final ArrayList<InstanceHandleImpl<I>> handles = new ArrayList<>();

    protected final AtomicBitSet changed = new AtomicBitSet();
    protected final AtomicBitSet deleted = new AtomicBitSet();

    protected final long instanceStride;
    // Set on worker threads at allocation time, cleared on the render thread after migration.
    protected final AtomicBitSet transientPages = new AtomicBitSet();
    // Volatile so worker-thread setters see grow events without needing the lock.
    protected volatile long[] slabBlocks = new long[0];
    // Plain (non-volatile) on purpose: written only by the render thread, read by workers in ensureSlabBlock --
    // the executor queue publishes the write happens-before. Out-of-plan callers must make this volatile.
    protected @Nullable HostSlab slabBuffer;

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
        HostSlab buf = slabBuffer;
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

    protected final @Nullable HostSlab prepareUpload() {
        long[] cur = slabBlocks;
        if (cur.length == 0) {
            return slabBuffer;
        }
        ensureSlabBufferInitialized();
        HostSlab buf = slabBuffer;
        // Safe: GPU and workers from the resize frame are done.
        buf.releaseRetired();
        boolean grew = buf.ensureCapacity(cur.length);
        long pageBytes = (long) PAGE_SIZE * instanceStride;

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
            slabBuffer = new HostSlab((long) PAGE_SIZE * instanceStride, INITIAL_BUFFER_PAGES);
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

        I instance;
        InstanceHandleImpl.Hidden<I> hidden;
        synchronized (lock) {
            // I think we need to lock to prevent wacky stuff from happening if the array gets resized.
            instance = instances.get(index);
            hidden = new InstanceHandleImpl.Hidden<>(recreate, instance, handle, slabPtrAt(index));
            notifyRemoval(index);
        }

        return hidden;
    }


    @Override
    public I createInstance() {
        var handle = new InstanceHandleImpl<>(this);
        I instance = type.create(handle);

        synchronized (lock) {
            addLocked(instance, handle, 0);
            return instance;
        }
    }

    @Override
    public InstanceHandleImpl.State<I> revealInstance(InstanceHandleImpl<I> handle, I instance, long source) {
        synchronized (lock) {
            addLocked(instance, handle, source);
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
            // UB: do nothing
            return;
        }

        // Should InstanceType have an isInstance method?
        @SuppressWarnings("unchecked") var handle = (InstanceHandleImpl<I>) instanceHandle;
        if (instance.type() != type) throw new IllegalArgumentException("Instance type migration");

        // No need to steal if this instance is already owned by this instancer.
        if (handle.state == this) {
            return;
        }
        // Not allowed to steal deleted instances.
        if (handle.state instanceof InstanceHandleImpl.Deleted) {
            return;
        }
        // No need to steal if the instance will recreate to us.
        if (handle.state instanceof InstanceHandleImpl.Hidden<I> hidden && recreate.equals(hidden.recreate())) {
            return;
        }

        // FIXME: in theory there could be a race condition here if the instance
        //  is somehow being stolen by 2 different instancers between threads.
        //  That seems kinda impossible so I'm fine leaving it as is for now.

        // Add the instance to this instancer.
        if (handle.state instanceof BaseInstancer<I> other) {
            int previousIndex = handle.index;
            long source = other.slabPtrAt(previousIndex);
            synchronized (lock) {
                addLocked(instance, handle, source);
                handle.state = this;
            }
            other.notifyRemoval(previousIndex);
        } else if (handle.state instanceof InstanceHandleImpl.Hidden<I> hidden) {
            hidden.retarget(recreate);
        }
    }

    /**
     * Calls must be synchronized on {@link #lock}.
     */
    private void addLocked(I instance, InstanceHandleImpl<I> handle, long source) {
        int index = instances.size();
        instances.ensureCapacity(index + 1);
        handles.ensureCapacity(index + 1);
        ensureSlabBlock(index);
        // ensureSlabBlock zero-fills a page only on FIRST touch, not per allocation -- a recycled slot keeps the
        // previous occupant's bytes. seed() implementors MUST NOT assume zeroed memory: write every field the
        // instance depends on (an empty seed is safe only if the geometry stays degenerate until posed).
        if (source == 0) type.seed().accept(slabPtrAt(index));
        else MemoryUtil.memCopy(source, slabPtrAt(index), type.layout().byteSize());
        instances.add(instance);
        handles.add(handle);
        handle.index = index;
        setIndexChanged(index);
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
            // Only clear instances that belong to this instancer.
            // If one of these handles was stolen by another instancer,
            // clearing it here would cause significant visual artifacts and instance leaks.
            // At the same time, we need to clear handles we own to prevent
            // instances from changing/deleting positions in this instancer that no longer exist.
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

    // Render-thread only.
    protected void freeGlResources() {
        if (slabBuffer != null) {
            slabBuffer.delete();
            slabBuffer = null;
        }
    }
}
