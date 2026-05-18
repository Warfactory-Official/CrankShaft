// SPDX-License-Identifier: Apache-2.0
package dev.engine_room.flywheel.lib.queues;

import jdk.internal.misc.Unsafe;

abstract class SpscArrayQueuePad0 {
    @SuppressWarnings("unused")
    long p00, p01, p02, p03, p04, p05, p06; // 56B
}

abstract class SpscArrayQueueColdFields<E> extends SpscArrayQueuePad0 {
    protected final long mask;
    protected final E[] buffer;
    protected final int lookAheadStep;

    @SuppressWarnings("unchecked")
    SpscArrayQueueColdFields(int actualCapacity) {
        this.mask = actualCapacity - 1;
        this.buffer = (E[]) new Object[actualCapacity];
        this.lookAheadStep = Math.clamp(actualCapacity / 4, 1, 4096);
    }
}

abstract class SpscArrayQueuePad1<E> extends SpscArrayQueueColdFields<E> {
    @SuppressWarnings("unused")
    long p10, p11, p12, p13, p14, p15, p16; // 56B

    SpscArrayQueuePad1(int actualCapacity) {
        super(actualCapacity);
    }
}

abstract class SpscArrayQueueProducerFields<E> extends SpscArrayQueuePad1<E> {
    static final Unsafe U = Unsafe.getUnsafe();
    static final long P_INDEX_OFFSET;
    static {
        try {
            P_INDEX_OFFSET = U.objectFieldOffset(SpscArrayQueueProducerFields.class.getDeclaredField("producerIndex"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected long producerLimit;
    private volatile long producerIndex;

    SpscArrayQueueProducerFields(int actualCapacity) {
        super(actualCapacity);
    }

    final long lpProducerIndex() {
        return U.getLong(this, P_INDEX_OFFSET);
    }

    final void soProducerIndex(long newValue) {
        U.putLongRelease(this, P_INDEX_OFFSET, newValue);
    }
}

abstract class SpscArrayQueuePad2<E> extends SpscArrayQueueProducerFields<E> {
    @SuppressWarnings("unused")
    long p20, p21, p22, p23, p24, p25, p26; // 56B

    SpscArrayQueuePad2(int actualCapacity) {
        super(actualCapacity);
    }
}

abstract class SpscArrayQueueConsumerFields<E> extends SpscArrayQueuePad2<E> {
    static final long C_INDEX_OFFSET;
    static {
        try {
            C_INDEX_OFFSET = U.objectFieldOffset(SpscArrayQueueConsumerFields.class.getDeclaredField("consumerIndex"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private volatile long consumerIndex;

    SpscArrayQueueConsumerFields(int actualCapacity) {
        super(actualCapacity);
    }

    final long lpConsumerIndex() {
        return U.getLong(this, C_INDEX_OFFSET);
    }

    final void soConsumerIndex(long newValue) {
        U.putLongRelease(this, C_INDEX_OFFSET, newValue);
    }
}

abstract class SpscArrayQueuePad3<E> extends SpscArrayQueueConsumerFields<E> {
    @SuppressWarnings("unused")
    long p30, p31, p32, p33, p34, p35, p36; // 56B

    SpscArrayQueuePad3(int actualCapacity) {
        super(actualCapacity);
    }
}

public final class SpscArrayQueue<E> extends SpscArrayQueuePad3<E> {

    static final long REF_ARRAY_BASE = U.arrayBaseOffset(Object[].class);
    static final int REF_ELEMENT_SHIFT;
    static {
        final int scale = U.arrayIndexScale(Object[].class);
        if (scale == 4) {
            REF_ELEMENT_SHIFT = 2;
        } else if (scale == 8) {
            REF_ELEMENT_SHIFT = 3;
        } else {
            throw new IllegalStateException("Unknown pointer size: " + scale);
        }
    }
    static final int CACHE_LINE = 64;

    static {
        final long pIdx = SpscArrayQueueProducerFields.P_INDEX_OFFSET;
        final long cIdx = SpscArrayQueueConsumerFields.C_INDEX_OFFSET;
        if (Math.abs(pIdx - cIdx) < CACHE_LINE) {
            throw new AssertionError("SpscArrayQueue layout check failed: "
                    + "producerIndex @ " + pIdx + " and consumerIndex @ " + cIdx
                    + " are less than " + CACHE_LINE + " bytes apart.");
        }
    }

    public SpscArrayQueue(final int capacity) {
        super(nextPow2(Math.max(capacity, 4)));
        if (capacity < 0 || capacity > (1 << 30)) {
            throw new IllegalArgumentException("capacity: " + capacity);
        }
    }

    private static int nextPow2(int v) {
        v--;
        v |= v >> 1; v |= v >> 2; v |= v >> 4; v |= v >> 8; v |= v >> 16;
        return v + 1;
    }

    private static long calcCircularRefElementOffset(long index, long mask) {
        return REF_ARRAY_BASE + ((index & mask) << REF_ELEMENT_SHIFT);
    }

    public int capacity() {
        return (int) (mask + 1);
    }

    public boolean isEmpty() {
        return lpProducerIndex() == lpConsumerIndex();
    }

    public boolean offer(final E e) {
        if (null == e) throw new NullPointerException();
        final E[] buffer = this.buffer;
        final long mask = this.mask;
        final long producerIndex = lpProducerIndex();

        if (producerIndex >= producerLimit && !offerSlowPath(buffer, mask, producerIndex)) {
            return false;
        }
        final long offset = calcCircularRefElementOffset(producerIndex, mask);
        U.putReferenceRelease(buffer, offset, e);
        soProducerIndex(producerIndex + 1);
        return true;
    }

    private boolean offerSlowPath(final E[] buffer, final long mask, final long producerIndex) {
        final int lookAheadStep = this.lookAheadStep;
        if (null == U.getReferenceVolatile(buffer, calcCircularRefElementOffset(producerIndex + lookAheadStep, mask))) {
            producerLimit = producerIndex + lookAheadStep;
            return true;
        }
        return null == U.getReferenceVolatile(buffer, calcCircularRefElementOffset(producerIndex, mask));
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        final long consumerIndex = lpConsumerIndex();
        final long offset = calcCircularRefElementOffset(consumerIndex, mask);
        final E[] buffer = this.buffer;
        final E e = (E) U.getReferenceVolatile(buffer, offset);
        if (null == e) return null;
        U.putReferenceRelease(buffer, offset, null);
        soConsumerIndex(consumerIndex + 1);
        return e;
    }
}
