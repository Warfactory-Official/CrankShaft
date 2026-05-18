// SPDX-License-Identifier: Apache-2.0
package dev.engine_room.flywheel.lib.queues;

import jdk.internal.misc.Unsafe;

import java.util.AbstractQueue;
import java.util.Iterator;

abstract class SpscUnboundedArrayQueuePad0<E> extends AbstractQueue<E> {
    @SuppressWarnings("unused")
    long p00, p01, p02, p03, p04, p05, p06; // 56B
}

abstract class SpscUnboundedArrayQueueConsumerColdFields<E> extends SpscUnboundedArrayQueuePad0<E> {
    protected long consumerMask;
    protected E[] consumerBuffer;
}

abstract class SpscUnboundedArrayQueueConsumerField<E> extends SpscUnboundedArrayQueueConsumerColdFields<E> {
    static final Unsafe U = Unsafe.getUnsafe();
    static final long C_INDEX_OFFSET;
    static {
        try {
            C_INDEX_OFFSET = U.objectFieldOffset(
                    SpscUnboundedArrayQueueConsumerField.class.getDeclaredField("consumerIndex"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private long consumerIndex;

    final long lvConsumerIndex() {
        return U.getLongVolatile(this, C_INDEX_OFFSET);
    }

    final long lpConsumerIndex() {
        return consumerIndex;
    }

    final void soConsumerIndex(long newValue) {
        U.putLongRelease(this, C_INDEX_OFFSET, newValue);
    }
}

abstract class SpscUnboundedArrayQueueL2Pad<E> extends SpscUnboundedArrayQueueConsumerField<E> {
    @SuppressWarnings("unused")
    long p10, p11, p12, p13, p14, p15, p16; // 56B

    @SuppressWarnings("unused")
    Object op10, op11, op12, op13, op14, op15, op16, op17,
            op18, op19, op1a, op1b, op1c, op1d, op1e, op1f;
}

abstract class SpscUnboundedArrayQueueProducerFields<E> extends SpscUnboundedArrayQueueL2Pad<E> {
    static final long P_INDEX_OFFSET;
    static {
        try {
            P_INDEX_OFFSET = U.objectFieldOffset(
                    SpscUnboundedArrayQueueProducerFields.class.getDeclaredField("producerIndex"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private long producerIndex;

    final long lvProducerIndex() {
        return U.getLongVolatile(this, P_INDEX_OFFSET);
    }

    final long lpProducerIndex() {
        return producerIndex;
    }

    final void soProducerIndex(long newValue) {
        U.putLongRelease(this, P_INDEX_OFFSET, newValue);
    }
}

abstract class SpscUnboundedArrayQueueProducerColdFields<E> extends SpscUnboundedArrayQueueProducerFields<E> {
    protected long producerBufferLimit;
    protected long producerMask;
    protected E[] producerBuffer;
}

public final class SpscUnboundedArrayQueue<E> extends SpscUnboundedArrayQueueProducerColdFields<E> {

    static final Object JUMP = new Object();

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
        final long pIdx = P_INDEX_OFFSET;
        final long cIdx = C_INDEX_OFFSET;
        if (Math.abs(pIdx - cIdx) < CACHE_LINE) {
            throw new AssertionError("SpscUnboundedArrayQueue layout check failed: "
                    + "producerIndex @ " + pIdx + " and consumerIndex @ " + cIdx
                    + " are less than " + CACHE_LINE + " bytes apart.");
        }
    }

    @SuppressWarnings("unchecked")
    public SpscUnboundedArrayQueue(int chunkSize) {
        final int chunkCapacity = Math.max(nextPow2(chunkSize), 16);
        final long mask = chunkCapacity - 1;
        // Capacity + 1: last slot reserved for the next-buffer link.
        final E[] buffer = (E[]) new Object[chunkCapacity + 1];
        producerBuffer = buffer;
        producerMask = mask;
        consumerBuffer = buffer;
        consumerMask = mask;
        producerBufferLimit = mask - 1;
    }

    private static int nextPow2(int v) {
        v = Math.max(v, 1);
        v--;
        v |= v >> 1; v |= v >> 2; v |= v >> 4; v |= v >> 8; v |= v >> 16;
        return v + 1;
    }

    private static long calcCircularRefElementOffset(long index, long mask) {
        return REF_ARRAY_BASE + ((index & mask) << REF_ELEMENT_SHIFT);
    }

    private static long nextArrayOffset(Object[] arr) {
        return REF_ARRAY_BASE + ((long) (arr.length - 1) << REF_ELEMENT_SHIFT);
    }

    @Override
    public boolean offer(final E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        final E[] buffer = producerBuffer;
        final long index = lpProducerIndex();
        final long mask = producerMask;
        final long offset = calcCircularRefElementOffset(index, mask);
        if (index < producerBufferLimit) {
            writeToQueue(buffer, e, index, offset);
            return true;
        }
        return offerColdPath(buffer, mask, index, offset, e);
    }

    private boolean offerColdPath(final E[] buffer, final long mask, final long pIndex, final long offset, final E e) {
        // Lookahead probe: if the slot `lookAheadStep` ahead is empty, raise producerBufferLimit
        // by that many to avoid re-entering the cold path until we get there.
        final long lookAheadStep = (mask + 1) >> 2;
        final long pBufferLimit = pIndex + lookAheadStep;

        if (null == U.getReferenceVolatile(
                buffer, calcCircularRefElementOffset(pBufferLimit, mask))) {
            producerBufferLimit = pBufferLimit - 1;
            writeToQueue(buffer, e, pIndex, offset);
        } else if (null == U.getReferenceVolatile(
                buffer, calcCircularRefElementOffset(pIndex + 1, mask))) {
            // Lookahead is occupied but the immediate next slot is free — keep going one at a time.
            writeToQueue(buffer, e, pIndex, offset);
        } else {
            // One slot left and the buffer is otherwise full — allocate a new linked buffer.
            @SuppressWarnings("unchecked")
            final E[] newBuffer = (E[]) new Object[(int) (mask + 2)];
            producerBuffer = newBuffer;
            producerBufferLimit = pIndex + mask - 1;
            linkOldToNew(pIndex, buffer, offset, newBuffer, offset, e);
        }
        return true;
    }

    private void writeToQueue(final E[] buffer, final E e, final long index, final long offset) {
        U.putReferenceRelease(buffer, offset, e);
        soProducerIndex(index + 1);
    }

    private void linkOldToNew(
            final long currIndex,
            final E[] oldBuffer, final long offset,
            final E[] newBuffer, final long offsetInNew,
            final E e) {
        U.putReferenceRelease(newBuffer, offsetInNew, e);
        // Link the new buffer into the old buffer's tail slot.
        U.putReferenceRelease(oldBuffer, nextArrayOffset(oldBuffer), newBuffer);
        // JUMP sentinel tells the consumer to switch buffers when it reaches this slot.
        U.putReferenceRelease(oldBuffer, offset, JUMP);
        soProducerIndex(currIndex + 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public E poll() {
        final E[] buffer = consumerBuffer;
        final long index = lpConsumerIndex();
        final long mask = consumerMask;
        final long offset = calcCircularRefElementOffset(index, mask);
        final Object e = U.getReferenceVolatile(buffer, offset);
        if (e == null) {
            return null;
        }
        if (e == JUMP) {
            return newBufferPoll(buffer, index);
        }
        soConsumerIndex(index + 1);
        U.putReferenceRelease(buffer, offset, null);
        return (E) e;
    }

    @SuppressWarnings("unchecked")
    private E newBufferPoll(final E[] buffer, final long index) {
        final E[] nextBuffer = lvNextArrayAndUnlink(buffer);
        consumerBuffer = nextBuffer;
        final long mask = nextBuffer.length - 2L;
        consumerMask = mask;
        final long offset = calcCircularRefElementOffset(index, mask);
        final Object n = U.getReferenceVolatile(nextBuffer, offset);
        if (n == null) {
            throw new IllegalStateException("new buffer must have at least one element");
        }
        soConsumerIndex(index + 1);
        U.putReferenceRelease(nextBuffer, offset, null);
        return (E) n;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E peek() {
        final E[] buffer = consumerBuffer;
        final long index = lpConsumerIndex();
        final long mask = consumerMask;
        final long offset = calcCircularRefElementOffset(index, mask);
        final Object e = U.getReferenceVolatile(buffer, offset);
        if (e == JUMP) {
            return newBufferPeek(buffer, index);
        }
        return (E) e;
    }

    @SuppressWarnings("unchecked")
    private E newBufferPeek(final E[] buffer, final long index) {
        final E[] nextBuffer = lvNextArrayAndUnlink(buffer);
        consumerBuffer = nextBuffer;
        final long mask = nextBuffer.length - 2L;
        consumerMask = mask;
        final long offset = calcCircularRefElementOffset(index, mask);
        return (E) U.getReferenceVolatile(nextBuffer, offset);
    }

    @SuppressWarnings("unchecked")
    private E[] lvNextArrayAndUnlink(final E[] curr) {
        final long offset = nextArrayOffset(curr);
        final E[] nextBuffer = (E[]) U.getReferenceVolatile(curr, offset);
        // Null the link slot so the dropped buffer doesn't pin the new one in old-gen.
        U.putReferenceRelease(curr, offset, null);
        return nextBuffer;
    }

    @Override
    public Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
        long after = lvConsumerIndex();
        long size;
        while (true) {
            final long before = after;
            final long pi = lvProducerIndex();
            after = lvConsumerIndex();
            if (before == after) {
                size = pi - after;
                break;
            }
        }
        if (size > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (size < 0) return 0;
        return (int) size;
    }

    @Override
    public boolean isEmpty() {
        final long ci = lvConsumerIndex();
        return ci == lvProducerIndex();
    }

    @Override
    public void clear() {
        while (poll() != null) {
        }
    }
}
