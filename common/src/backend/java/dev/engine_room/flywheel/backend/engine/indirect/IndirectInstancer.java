package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.util.AtomicBitSet;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.*;

public class IndirectInstancer<I extends Instance> extends AbstractInstancer<I> {
    private static final int DIRTY_SHARD_COUNT;
    private static final int DIRTY_SHARD_MASK;
    private static final int DIRTY_SHARD_STRIDE = 8;
    private static final int DIRTY_MIN_OFF = 2;
    private static final int DIRTY_MAX_OFF = 4;
    private static final VarHandle DIRTY_LA = MethodHandles.arrayElementVarHandle(long[].class);

    static {
        int parallelism = ForkJoinPool.commonPool().getParallelism();
        int n = parallelism > 1 ? Integer.highestOneBit(parallelism - 1) << 1 : 4;
        DIRTY_SHARD_COUNT = Math.max(4, n);
        DIRTY_SHARD_MASK = DIRTY_SHARD_COUNT - 1;
    }

    private final long instanceStride;
    private final List<IndirectDraw> associatedDraws = new ArrayList<>();
    private final Vector4fc boundingSphere;
    private final boolean noOcclusionCull;
    private final AtomicReference<InstancePage<I>[]> pages = new AtomicReference<>(pageArray(0));
    private final AtomicInteger instanceCount = new AtomicInteger(0);
    /**
     * The set of pages whose count changed and thus need their descriptor re-uploaded.
     */
    private final AtomicBitSet validityChanged = new AtomicBitSet();
    /**
     * The set of pages whose content changed and thus need their instances re-uploaded.
     * Note that we don't re-upload for deletions, as the memory becomes invalid and masked out by the validity bits.
     */
    private final AtomicBitSet contentsChanged = new AtomicBitSet();
    /**
     * The set of pages that are entirely full.
     * We scan the clear bits of this set when trying to add an instance.
     */
    private final AtomicBitSet fullPages = new AtomicBitSet();
    /**
     * The set of mergable pages. A page is mergeable if it is not empty and has 16 or fewer instances.
     * These constraints are set so that we can guarantee that merging two pages leaves one entirely empty,
     * but we also don't want to waste work merging into pages that are already empty.
     */
    private final AtomicBitSet mergeablePages = new AtomicBitSet();
    private final long[] validityDirtyShards = freshDirtyShards();
    private final long[] contentsDirtyShards = freshDirtyShards();
    private final BitSet carriedPages = new BitSet();
    private final BitSet carryScratch = new BitSet();
    private final SlabFactory slabFactory;
    public ObjectMapping mapping;
    private int modelIndex = -1;
    private int baseInstance = -1;
    private @Nullable Slab slabBuffer;

    public IndirectInstancer(InstancerKey<I> key, Recreate<I> recreate, SlabFactory slabFactory) {
        super(key, recreate);
        instanceStride = MoreMath.align4(type.layout()
                                             .byteSize());
        boundingSphere = key.model().boundingSphere();
        noOcclusionCull = key.model()
                             .meshes()
                             .stream()
                             .anyMatch(mesh -> mesh.material()
                                                   .depthTest() == DepthTest.OFF);
        this.slabFactory = slabFactory;
    }

    private static long[] freshDirtyShards() {
        long[] s = new long[DIRTY_SHARD_COUNT * DIRTY_SHARD_STRIDE];
        for (int i = 0; i < DIRTY_SHARD_COUNT; i++) {
            s[i * DIRTY_SHARD_STRIDE + DIRTY_MIN_OFF] = Integer.MAX_VALUE;
            s[i * DIRTY_SHARD_STRIDE + DIRTY_MAX_OFF] = -1L;
        }
        return s;
    }

    private static void markDirtyShard(long[] shards, int pageNo) {
        int base = (Thread.currentThread().hashCode() & DIRTY_SHARD_MASK) * DIRTY_SHARD_STRIDE;
        int minIdx = base + DIRTY_MIN_OFF;
        int maxIdx = base + DIRTY_MAX_OFF;
        long v = pageNo;
        long cur;
        do {
            cur = (long) DIRTY_LA.getOpaque(shards, minIdx);
            if (v >= cur) break;
        } while (!DIRTY_LA.compareAndSet(shards, minIdx, cur, v));
        do {
            cur = (long) DIRTY_LA.getOpaque(shards, maxIdx);
            if (v <= cur) break;
        } while (!DIRTY_LA.compareAndSet(shards, maxIdx, cur, v));
    }

    private static int reduceDirtyMin(long[] shards) {
        long min = Integer.MAX_VALUE;
        for (int s = 0; s < DIRTY_SHARD_COUNT; s++) {
            long v = (long) DIRTY_LA.getAcquire(shards, s * DIRTY_SHARD_STRIDE + DIRTY_MIN_OFF);
            if (v < min) min = v;
        }
        return (int) min;
    }

    private static int reduceDirtyMax(long[] shards) {
        long max = -1L;
        for (int s = 0; s < DIRTY_SHARD_COUNT; s++) {
            long v = (long) DIRTY_LA.getAcquire(shards, s * DIRTY_SHARD_STRIDE + DIRTY_MAX_OFF);
            if (v > max) max = v;
        }
        return (int) max;
    }

    private static void clearDirtyShards(long[] shards) {
        for (int s = 0; s < DIRTY_SHARD_COUNT; s++) {
            DIRTY_LA.setRelease(shards, s * DIRTY_SHARD_STRIDE + DIRTY_MIN_OFF, (long) Integer.MAX_VALUE);
            DIRTY_LA.setRelease(shards, s * DIRTY_SHARD_STRIDE + DIRTY_MAX_OFF, -1L);
        }
    }

    @SuppressWarnings("unchecked")
    private static <I extends Instance> InstancePage<I>[] pageArray(int length) {
        return new InstancePage[length];
    }

    @SuppressWarnings("unchecked")
    private static <I extends Instance> I[] instanceArray() {
        return (I[]) new Instance[PAGE_SIZE];
    }

    @SuppressWarnings("unchecked")
    private static <I extends Instance> InstanceHandleImpl<I>[] handleArray() {
        return new InstanceHandleImpl[PAGE_SIZE];
    }

    @Nullable
    public static IndirectInstancer<?> fromState(InstanceHandleImpl.State<?> handle) {
        if (handle instanceof InstancePage<?> instancer) {
            return instancer.parent;
        }
        return null;
    }

    private static boolean isFull(int valid) {
        return valid == 0xFFFFFFFF;
    }

    private static boolean isEmpty(int valid) {
        return valid == 0;
    }

    private static boolean isMergeable(int valid) {
        return !isEmpty(valid) && Integer.bitCount(valid) <= 16;
    }

    public void addDraw(IndirectDraw draw) {
        associatedDraws.add(draw);
    }

    public List<IndirectDraw> draws() {
        return associatedDraws;
    }

    public void update(int modelIndex, int baseInstance) {
        this.baseInstance = baseInstance;

        var sameModelIndex = this.modelIndex == modelIndex;
        int dirtyMin = sameModelIndex ? reduceDirtyMin(validityDirtyShards) : 0;
        if (sameModelIndex && dirtyMin == Integer.MAX_VALUE) {
            return;
        }

        this.modelIndex = modelIndex;

        var pages = this.pages.get();
        mapping.updateCount(pages.length);

        if (sameModelIndex) {
            int dirtyMax = Math.min(reduceDirtyMax(validityDirtyShards), pages.length - 1);
            for (int page = validityChanged.nextSetBit(
                    dirtyMin); page >= 0 && page <= dirtyMax; page = validityChanged.nextSetBit(page + 1)) {
                mapping.updatePage(page, modelIndex, pages[page].valid.get());
            }
        } else {
            // Need to update all pages since the model index changed.
            for (int i = 0; i < pages.length; i++) {
                mapping.updatePage(i, modelIndex, pages[i].valid.get());
            }
        }

        validityChanged.clear();
        clearDirtyShards(validityDirtyShards);
    }

    public void writeModel(long ptr) {
        MemoryUtil.memPutInt(ptr, 0); // instanceCount - to be incremented by the cull shader
        MemoryUtil.memPutInt(ptr + 4, baseInstance); // baseInstance
        MemoryUtil.memPutInt(ptr + 8, environment.matrixIndex() | (noOcclusionCull ? Integer.MIN_VALUE : 0));
        MemoryUtil.memPutFloat(ptr + 12, boundingSphere.x()); // boundingSphere
        MemoryUtil.memPutFloat(ptr + 16, boundingSphere.y());
        MemoryUtil.memPutFloat(ptr + 20, boundingSphere.z());
        MemoryUtil.memPutFloat(ptr + 24, boundingSphere.w());
    }

    public void uploadInstances(SlabPageCopier copier) {
        uploadInstances(copier, false);
    }

    public void uploadInstances(SlabPageCopier copier, boolean carry) {
        if (carry) {
            carryScratch.clear();
            for (int page = contentsChanged.nextSetBit(0); page >= 0; page = contentsChanged.nextSetBit(page + 1)) {
                carryScratch.set(page);
            }
            for (int page = carriedPages.nextSetBit(0); page >= 0; page = carriedPages.nextSetBit(page + 1)) {
                contentsChanged.set(page);
                markDirtyShard(contentsDirtyShards, page);
            }
            carriedPages.clear();
            carriedPages.or(carryScratch);
        }
        int dirtyMin = reduceDirtyMin(contentsDirtyShards);
        if (dirtyMin == Integer.MAX_VALUE) {
            return;
        }

        var pages = this.pages.get();
        int dirtyMax = Math.min(reduceDirtyMax(contentsDirtyShards), pages.length - 1);

        prepareUpload(pages);

        long pageBytes = PAGE_SIZE * instanceStride;

        int mappedCount = mapping.pageCount();
        boolean deferredAny = false;

        int spanStart = contentsChanged.nextSetBit(dirtyMin);
        while (spanStart >= 0 && spanStart <= dirtyMax) {
            int spanEnd = contentsChanged.nextClearBit(spanStart) - 1;
            if (spanEnd > dirtyMax) {
                spanEnd = dirtyMax;
            }
            long srcOff = (long) spanStart * pageBytes;
            slabBuffer.flushRange(srcOff, (long) (spanEnd - spanStart + 1) * pageBytes);

            for (int page = spanStart; page <= spanEnd; page++) {
                if (page >= mappedCount) {
                    deferredAny = true;
                    continue;
                }
                long baseByte = mapping.page2ByteOffset(page);
                if (baseByte < 0) {
                    continue;
                }
                copier.copyPage(slabBuffer, (long) page * pageBytes, baseByte, pageBytes);
            }
            spanStart = contentsChanged.nextSetBit(spanEnd + 1);
        }

        contentsChanged.clear();
        clearDirtyShards(contentsDirtyShards);

        if (deferredAny) {
            for (int page = Math.max(mappedCount, 0); page < pages.length; page++) {
                contentsChanged.set(page);
                markDirtyShard(contentsDirtyShards, page);
            }
        }
    }

    private void prepareUpload(InstancePage<I>[] pages) {
        if (slabBuffer == null) {
            slabBuffer = slabFactory.create((long) PAGE_SIZE * instanceStride, INITIAL_BUFFER_PAGES);
        }
        slabBuffer.releaseRetired();
        boolean grew = slabBuffer.ensureCapacity(pages.length);
        long pageBytes = (long) PAGE_SIZE * instanceStride;
        for (InstancePage<I> page : pages) {
            if (page.transient_) {
                long mappedPtr = slabBuffer.ptrForPage(page.pageNo);
                MemoryUtil.memCopy(page.slabPtr, mappedPtr, pageBytes);
                FlwMemoryTracker.free(page.slabPtr);
                FlwMemoryTracker._freeCpuMemory(pageBytes);
                page.slabPtr = mappedPtr;
                page.transient_ = false;
            } else if (grew) {
                page.slabPtr = slabBuffer.ptrForPage(page.pageNo);
            }
        }
    }

    @Override
    public void parallelUpdate() {
        var pages = this.pages.get();

        // Clear the tail so we don't have to worry about out of bounds access.
        mergeablePages.clear(pages.length, mergeablePages.currentCapacity());

        int page = 0;
        while (mergeablePages.cardinality() > 1) {
            page = mergeablePages.nextSetBit(page);
            if (page < 0) {
                break;
            }

            // Find the next mergeable page.
            int next = mergeablePages.nextSetBit(page + 1);
            if (next < 0) {
                break;
            }

            // Try to merge the pages.
            pages[page].takeFrom(pages[next]);
        }
    }

    @Override
    public void delete() {
        for (IndirectDraw draw : draws()) {
            draw.delete();
        }

        for (InstancePage<I> page : pages.getAndSet(pageArray(0))) {
            page.dispose();
        }

        freeGlResources();

        if (mapping != null) {
            mapping.delete();
            mapping = null;
        }
    }

    public int modelIndex() {
        return modelIndex;
    }

    public int baseInstance() {
        return baseInstance;
    }

    public int local2ObjectUintOffset(int instanceIndex) {
        return mapping.objectIndex2UintOffset(instanceIndex);
    }

    @Override
    public I createInstance() {
        var handle = new InstanceHandleImpl<I>(null);
        I instance = type.create(handle);

        addInner(instance, handle);

        return instance;
    }

    @Override
    public InstanceHandleImpl.State<I> revealInstance(InstanceHandleImpl<I> handle, I instance) {
        addInner(instance, handle);
        return handle.state;
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
        if (handle.state instanceof InstancePage<?> other) {
            if (other.parent == this) {
                return;
            }

            // Remove the instance from its old instancer.
            // This won't have any unwanted effect when the old instancer
            // is filtering deleted instances later, so is safe.
            other.setDeleted(handle.index);

            // Only lock now that we'll be mutating our state.
            addInner(instance, handle);
        } else if (handle.state instanceof InstanceHandleImpl.Hidden<I>) {
            handle.state = new InstanceHandleImpl.Hidden<>(recreate, instance);
        }
    }

    private void addInner(I instance, InstanceHandleImpl<I> handle) {
        // Outer loop:
        // - try to find an empty space
        // - or grow the page array if we can't
        // - add the instance to the new page, or try again
        while (true) {
            var pages = this.pages.get();

            // First, try to find a page with space.
            for (int i = fullPages.nextClearBit(0); i < pages.length; i = fullPages.nextClearBit(i + 1)) {
                // It may have been filled in while we were searching, but hopefully not.
                if (pages[i].add(instance, handle)) {
                    return;
                }
            }

            // If we're here, all other pages are full
            // If we hit this on the second iteration of the outer loop then `pages` is once again full.
            var desiredLength = pages.length + 1;

            // Inner loop: grow the page array. This is very similar to the logic in AtomicBitSet.
            while (pages.length < desiredLength) {
                // Thread safety: segments contains all pages from the currently visible pages, plus extra.
                // all pages in the currently visible pages are canonical and will not change.
                // Can't just `new InstancePage[]` because it has a generic parameter.
                InstancePage<I>[] newPages = pageArray(desiredLength);

                System.arraycopy(pages, 0, newPages, 0, pages.length);
                InstancePage<I> appended = new InstancePage<>(this, pages.length);
                newPages[pages.length] = appended;

                // because we are using a compareAndSet, if this thread "wins the race" and successfully sets this variable, then the new page becomes canonical.
                if (this.pages.compareAndSet(pages, newPages)) {
                    pages = newPages;
                } else {
                    appended.dispose();
                    // If we "lose the race" and are growing the AtomicBitset segments larger,
                    // then we will gather the new canonical pages from the update which we missed on the next iteration of this loop.
                    // The new page will be discarded and never seen again.
                    pages = this.pages.get();
                }
            }

            // Shortcut: try to add the instance to the last page.
            // Technically we could just let the outer loop go again, but that
            // involves a good bit of work just to likely get back here.
            if (pages[pages.length - 1].add(instance, handle)) {
                return;
            }
            // It may be the case that many other instances were added in the same instant.
            // We can still lose this race, though it is very unlikely.
        }
    }

    @Override
    public int instanceCount() {
        return instanceCount.get();
    }

    @Override
    public void clear() {
        InstancePage<I>[] old = this.pages.getAndSet(pageArray(0));
        for (InstancePage<I> page : old) {
            page.dispose();
        }
        contentsChanged.clear();
        validityChanged.clear();
        fullPages.clear();
        mergeablePages.clear();
        clearDirtyShards(contentsDirtyShards);
        clearDirtyShards(validityDirtyShards);
        carriedPages.clear();
    }

    private void freeGlResources() {
        if (slabBuffer != null) {
            slabBuffer.delete();
            slabBuffer = null;
        }
    }

    private static final class InstancePage<I extends Instance> implements InstanceHandleImpl.State<I> {
        private final IndirectInstancer<I> parent;
        private final int pageNo;
        private final I[] instances;
        // Handles are only read in #takeFrom. It would be nice to avoid tracking these at all.
        private final InstanceHandleImpl<I>[] handles;
        /**
         * A bitset describing which indices in the instances/handles arrays contain live instances.
         */
        private final AtomicInteger valid;
        long slabPtr;
        boolean transient_;

        private InstancePage(IndirectInstancer<I> parent, int pageNo) {
            this.parent = parent;
            this.pageNo = pageNo;
            this.slabPtr = FlwMemoryTracker.calloc(PAGE_SIZE, parent.instanceStride);
            FlwMemoryTracker._allocCpuMemory(PAGE_SIZE * parent.instanceStride);
            this.transient_ = true;
            this.instances = instanceArray();
            this.handles = handleArray();
            this.valid = new AtomicInteger(0);
        }

        private void dispose() {
            if (transient_) {
                FlwMemoryTracker.free(slabPtr);
                FlwMemoryTracker._freeCpuMemory(PAGE_SIZE * parent.instanceStride);
                transient_ = false;
            }
        }

        /**
         * Attempt to add the given instance/handle to this page.
         *
         * @param instance The instance to add
         * @param handle   The instance's handle
         * @return true if the instance was added, false if the page is full
         */
        public boolean add(I instance, InstanceHandleImpl<I> handle) {
            // Thread safety: we loop until we either win the race and add the given instance, or we
            // run out of space because other threads trying to add at the same time.
            while (true) {
                int currentValue = valid.get();
                if (isFull(currentValue)) {
                    // The page is full, must search elsewhere
                    return false;
                }

                // determine what the new long value will be after we set the appropriate bit.
                int index = Integer.numberOfTrailingZeros(~currentValue);

                int newValue = currentValue | (1 << index);

                // if no other thread has modified the value since we read it, we won the race and we are done.
                if (valid.compareAndSet(currentValue, newValue)) {
                    instances[index] = instance;
                    handles[index] = handle;
                    handle.state = this;
                    // Handle index is unique amongst all pages of this instancer.
                    handle.index = local2HandleIndex(index);

                    parent.type.seed().accept(slabPtr + (long) index * parent.instanceStride);

                    parent.contentsChanged.set(pageNo);
                    markDirtyShard(parent.contentsDirtyShards, pageNo);
                    parent.validityChanged.set(pageNo);
                    markDirtyShard(parent.validityDirtyShards, pageNo);
                    // The page is now full, mark it so in the bitset.
                    // This is safe because only one bit position changes at a time.
                    if (isFull(newValue)) {
                        parent.fullPages.set(pageNo);
                    }
                    if (isMergeable(newValue)) {
                        parent.mergeablePages.set(pageNo);
                    }

                    parent.instanceCount.incrementAndGet();
                    return true;
                }
            }
        }

        private int local2HandleIndex(int index) {
            return (pageNo << LOG_2_PAGE_SIZE) + index;
        }

        @Override
        public InstanceHandleImpl.State<I> setChanged(int index) {
            parent.contentsChanged.set(pageNo);
            markDirtyShard(parent.contentsDirtyShards, pageNo);
            return this;
        }

        @Override
        public long slabPtrAt(int index) {
            return slabPtr + (long) (index & PAGE_MASK) * parent.instanceStride;
        }

        @Override
        public InstanceHandleImpl.State<I> setDeleted(int index) {
            int localIndex = index % PAGE_SIZE;

            clear(localIndex);

            return InstanceHandleImpl.Deleted.instance();
        }

        @Override
        public InstanceHandleImpl.State<I> setVisible(InstanceHandleImpl<I> handle, int index, boolean visible) {
            if (visible) {
                return this;
            }

            int localIndex = index % PAGE_SIZE;

            var out = instances[localIndex];

            clear(localIndex);

            return new InstanceHandleImpl.Hidden<>(parent.recreate, out);
        }

        private void clear(int localIndex) {
            instances[localIndex] = null;
            handles[localIndex] = null;

            while (true) {
                int currentValue = valid.get();
                int newValue = currentValue & ~(1 << localIndex);

                if (valid.compareAndSet(currentValue, newValue)) {
                    parent.validityChanged.set(pageNo);
                    markDirtyShard(parent.validityDirtyShards, pageNo);
                    if (isMergeable(newValue)) {
                        parent.mergeablePages.set(pageNo);
                    }
                    // Set full page last so that other threads don't race to set the other bitsets.
                    parent.fullPages.clear(pageNo);
                    parent.instanceCount.decrementAndGet();
                    break;
                }
            }
        }

        /**
         * Only call this on 2 pages that are mergeable.
         *
         * @param other The page to take instances from.
         */
        private void takeFrom(InstancePage<I> other) {
            // Fill the holes in this page with instances from the other page.

            int valid = this.valid.get();

            if (isFull(valid)) {
                // We got filled after being marked mergeable, nothing to do
                parent.mergeablePages.clear(pageNo);
                return;
            }

            int otherValid = other.valid.get();

            for (int i = 0; i < PAGE_SIZE; i++) {
                int mask = 1 << i;

                // Find set bits in the other page.
                if ((otherValid & mask) == 0) {
                    continue;
                }

                int writePos = Integer.numberOfTrailingZeros(~valid);

                instances[writePos] = other.instances[i];
                handles[writePos] = other.handles[i];

                handles[writePos].state = this;
                handles[writePos].index = local2HandleIndex(writePos);

                MemoryUtil.memCopy(other.slabPtr + (long) i * parent.instanceStride,
                        this.slabPtr + (long) writePos * parent.instanceStride,
                        parent.instanceStride);

                // Clear out the other page.
                otherValid &= ~mask;
                other.handles[i] = null;
                other.instances[i] = null;

                // Set the bit in this page so we can find the next write position.
                valid |= 1 << writePos;

                // If we're full, we're done.
                if (isFull(valid)) {
                    break;
                }
            }

            this.valid.set(valid);
            other.valid.set(otherValid);

            // If the other page was quite empty we may still be mergeable.
            parent.mergeablePages.set(pageNo, isMergeable(valid));

            // We definitely changed the contents and validity of this page.
            parent.contentsChanged.set(pageNo);
            markDirtyShard(parent.contentsDirtyShards, pageNo);
            parent.validityChanged.set(pageNo);
            markDirtyShard(parent.validityDirtyShards, pageNo);

            // The other page will end up empty, so the validity changes and it's no longer mergeable.
            // Also clear the changed bit so we don't re-upload the instances.
            parent.contentsChanged.clear(other.pageNo);
            parent.validityChanged.set(other.pageNo);
            markDirtyShard(parent.validityDirtyShards, other.pageNo);
            parent.mergeablePages.clear(other.pageNo);

            if (isFull(valid)) {
                parent.fullPages.set(pageNo);
            }
        }
    }
}
