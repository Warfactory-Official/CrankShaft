// SPDX-License-Identifier: Apache-2.0
package dev.engine_room.flywheel.lib.queues;

import jdk.internal.misc.Unsafe;

class MpUnboundedXaddChunk<R extends MpUnboundedXaddChunk<R, E>, E> {
    public static final int NOT_USED = -1;

    static final Unsafe U = Unsafe.getUnsafe();
    static final long PREV_OFFSET;
    static final long NEXT_OFFSET;
    static final long INDEX_OFFSET;

    static final long REF_ARRAY_BASE = U.arrayBaseOffset(Object[].class);
    static final int REF_ELEMENT_SHIFT;
    static {
        try {
            PREV_OFFSET = U.objectFieldOffset(MpUnboundedXaddChunk.class.getDeclaredField("prev"));
            NEXT_OFFSET = U.objectFieldOffset(MpUnboundedXaddChunk.class.getDeclaredField("next"));
            INDEX_OFFSET = U.objectFieldOffset(MpUnboundedXaddChunk.class.getDeclaredField("index"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
        final int scale = U.arrayIndexScale(Object[].class);
        if (scale == 4) REF_ELEMENT_SHIFT = 2;
        else if (scale == 8) REF_ELEMENT_SHIFT = 3;
        else throw new IllegalStateException("Unknown pointer size: " + scale);
    }

    private final boolean pooled;
    final Object[] buffer;

    private volatile R prev;
    private volatile long index;
    private volatile R next;

    protected MpUnboundedXaddChunk(long index, R prev, int size, boolean pooled) {
        this.buffer = new Object[size];
        soPrev(prev);
        spIndex(index);
        this.pooled = pooled;
    }

    static long calcRefElementOffset(int index) {
        return REF_ARRAY_BASE + (((long) index) << REF_ELEMENT_SHIFT);
    }

    public final boolean isPooled() {
        return pooled;
    }

    public final long lvIndex() {
        return index;
    }

    public final void soIndex(long newIndex) {
        U.putLongRelease(this, INDEX_OFFSET, newIndex);
    }

    final void spIndex(long newIndex) {
        U.putLong(this, INDEX_OFFSET, newIndex);
    }

    public final R lvNext() {
        return next;
    }

    public final void soNext(R value) {
        U.putReferenceRelease(this, NEXT_OFFSET, value);
    }

    public final R lvPrev() {
        return prev;
    }

    public final void soPrev(R value) {
        // jctools quirk: name suggests release, but is plain. Safe — soIndex publishes prev.
        U.putReference(this, PREV_OFFSET, value);
    }

    public final void soElement(int idx, E e) {
        U.putReferenceRelease(buffer, calcRefElementOffset(idx), e);
    }

    @SuppressWarnings("unchecked")
    public final E lvElement(int idx) {
        return (E) U.getReferenceVolatile(buffer, calcRefElementOffset(idx));
    }

    @SuppressWarnings("unchecked")
    public final E spinForElement(int idx, boolean isNull) {
        final Object[] buf = this.buffer;
        final long off = calcRefElementOffset(idx);
        Object v;
        do {
            v = U.getReferenceVolatile(buf, off);
            Thread.onSpinWait();
        } while (isNull != (v == null));
        return (E) v;
    }
}
