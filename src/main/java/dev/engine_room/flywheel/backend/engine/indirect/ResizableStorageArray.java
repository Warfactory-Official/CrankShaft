package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.lib.math.MoreMath;

/**
 * A buffer that is aware of its content's stride with some control over how it grows.
 */
public class ResizableStorageArray {
    private static final double DEFAULT_GROWTH_FACTOR = 1.25;
    private final ResizableStorageBuffer buffer;
    private final long stride;
    private final double growthFactor;
    private final SharedStorageArena.Region region;

    private long capacity;

    public ResizableStorageArray(long stride) {
        this(stride, DEFAULT_GROWTH_FACTOR);
    }

    public ResizableStorageArray(long stride, double growthFactor) {
        this(stride, growthFactor, null);
    }

    public ResizableStorageArray(long stride, double growthFactor, SharedStorageArena arena) {
        this.stride = stride;
        this.growthFactor = growthFactor;

        if (stride <= 0) {
            throw new IllegalArgumentException("Stride must be positive!");
        }

        if (growthFactor <= 1) {
            throw new IllegalArgumentException("Growth factor must be greater than 1!");
        }

        this.buffer = arena == null ? new ResizableStorageBuffer() : arena.buffer;
        region = arena == null ? null : arena.region();
    }

    public int handle() {
        return buffer.handle();
    }

    public long stride() {
        return stride;
    }

    public long capacity() {
        return capacity;
    }

    public long byteCapacity() {
        return region == null ? buffer.capacity() : region.capacity();
    }

    public long byteOffset() {
        return region == null ? 0 : region.offset();
    }

    public void ensureCapacity(long capacity) {
        if (capacity > this.capacity) {
            long newCapacity = grow(capacity);
            if (region == null) buffer.ensureCapacity(stride * newCapacity);
            else region.ensureCapacity(stride * newCapacity);
            this.capacity = newCapacity;
        }
    }

    public void delete() {
        if (region == null) buffer.delete();
        else region.delete();
    }

    private long grow(long capacity) {
        return MoreMath.ceilLong(capacity * growthFactor);
    }
}
