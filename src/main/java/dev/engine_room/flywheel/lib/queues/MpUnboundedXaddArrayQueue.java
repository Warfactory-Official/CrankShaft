// SPDX-License-Identifier: Apache-2.0
package dev.engine_room.flywheel.lib.queues;

import jdk.internal.misc.Unsafe;

import java.util.AbstractQueue;
import java.util.Iterator;

abstract class MpUnboundedXaddArrayQueuePad1<R extends MpUnboundedXaddChunk<R, E>, E> extends AbstractQueue<E> {
    @SuppressWarnings("unused")
    long p00, p01, p02, p03, p04, p05, p06; // 56B
}

abstract class MpUnboundedXaddArrayQueueProducerFields<R extends MpUnboundedXaddChunk<R, E>, E>
        extends MpUnboundedXaddArrayQueuePad1<R, E> {
    static final Unsafe U = Unsafe.getUnsafe();
    static final long P_INDEX_OFFSET;
    static {
        try {
            P_INDEX_OFFSET = U.objectFieldOffset(
                    MpUnboundedXaddArrayQueueProducerFields.class.getDeclaredField("producerIndex"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    volatile long producerIndex;

    public final long lvProducerIndex() {
        return producerIndex;
    }

    final long getAndIncrementProducerIndex() {
        return U.getAndAddLong(this, P_INDEX_OFFSET, 1L);
    }

    final long getAndAddProducerIndex(long delta) {
        return U.getAndAddLong(this, P_INDEX_OFFSET, delta);
    }
}

abstract class MpUnboundedXaddArrayQueuePad2<R extends MpUnboundedXaddChunk<R, E>, E>
        extends MpUnboundedXaddArrayQueueProducerFields<R, E> {
    @SuppressWarnings("unused")
    long p10, p11, p12, p13, p14, p15, p16; // 56B
}

abstract class MpUnboundedXaddArrayQueueProducerChunk<R extends MpUnboundedXaddChunk<R, E>, E>
        extends MpUnboundedXaddArrayQueuePad2<R, E> {
    static final long P_CHUNK_OFFSET;
    static final long P_CHUNK_INDEX_OFFSET;
    static {
        try {
            P_CHUNK_OFFSET = MpUnboundedXaddArrayQueueProducerFields.U.objectFieldOffset(
                    MpUnboundedXaddArrayQueueProducerChunk.class.getDeclaredField("producerChunk"));
            P_CHUNK_INDEX_OFFSET = MpUnboundedXaddArrayQueueProducerFields.U.objectFieldOffset(
                    MpUnboundedXaddArrayQueueProducerChunk.class.getDeclaredField("producerChunkIndex"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    volatile R producerChunk;
    volatile long producerChunkIndex;

    final R lvProducerChunk() {
        return producerChunk;
    }

    final void soProducerChunk(R chunk) {
        MpUnboundedXaddArrayQueueProducerFields.U.putReferenceRelease(this, P_CHUNK_OFFSET, chunk);
    }

    final long lvProducerChunkIndex() {
        return producerChunkIndex;
    }

    final boolean casProducerChunkIndex(long expected, long value) {
        return MpUnboundedXaddArrayQueueProducerFields.U.compareAndSetLong(this, P_CHUNK_INDEX_OFFSET, expected, value);
    }

    final void soProducerChunkIndex(long value) {
        MpUnboundedXaddArrayQueueProducerFields.U.putLongRelease(this, P_CHUNK_INDEX_OFFSET, value);
    }
}

abstract class MpUnboundedXaddArrayQueuePad3<R extends MpUnboundedXaddChunk<R, E>, E>
        extends MpUnboundedXaddArrayQueueProducerChunk<R, E> {
    @SuppressWarnings("unused")
    long p20, p21, p22, p23, p24, p25, p26; // 56B — separates long-group producer fields from consumer fields

    // Reference-group padding: producerChunk and consumerChunk both live in the ref group
    // under compressed oops. Long padding alone doesn't isolate them across the size-grouped
    // field layout. 16 refs = 64 B compressed / 128 B raw — enough for one full cache line.
    @SuppressWarnings("unused")
    Object op20, op21, op22, op23, op24, op25, op26, op27,
            op28, op29, op2a, op2b, op2c, op2d, op2e, op2f;
}

abstract class MpUnboundedXaddArrayQueueConsumerFields<R extends MpUnboundedXaddChunk<R, E>, E>
        extends MpUnboundedXaddArrayQueuePad3<R, E> {
    static final long C_INDEX_OFFSET;
    static final long C_CHUNK_OFFSET;
    static {
        try {
            C_INDEX_OFFSET = MpUnboundedXaddArrayQueueProducerFields.U.objectFieldOffset(
                    MpUnboundedXaddArrayQueueConsumerFields.class.getDeclaredField("consumerIndex"));
            C_CHUNK_OFFSET = MpUnboundedXaddArrayQueueProducerFields.U.objectFieldOffset(
                    MpUnboundedXaddArrayQueueConsumerFields.class.getDeclaredField("consumerChunk"));
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    long consumerIndex;
    volatile R consumerChunk;

    public final long lvConsumerIndex() {
        return MpUnboundedXaddArrayQueueProducerFields.U.getLongVolatile(this, C_INDEX_OFFSET);
    }

    final long lpConsumerIndex() {
        return consumerIndex;
    }

    final void soConsumerIndex(long newValue) {
        MpUnboundedXaddArrayQueueProducerFields.U.putLongRelease(this, C_INDEX_OFFSET, newValue);
    }

    @SuppressWarnings("unchecked")
    final R lpConsumerChunk() {
        return (R) MpUnboundedXaddArrayQueueProducerFields.U.getReference(this, C_CHUNK_OFFSET);
    }

    final R lvConsumerChunk() {
        return consumerChunk;
    }

    final void soConsumerChunk(R newValue) {
        MpUnboundedXaddArrayQueueProducerFields.U.putReferenceRelease(this, C_CHUNK_OFFSET, newValue);
    }
}

abstract class MpUnboundedXaddArrayQueuePad5<R extends MpUnboundedXaddChunk<R, E>, E>
        extends MpUnboundedXaddArrayQueueConsumerFields<R, E> {
    @SuppressWarnings("unused")
    long p30, p31, p32, p33, p34, p35, p36; // 56B
}

abstract class MpUnboundedXaddArrayQueue<R extends MpUnboundedXaddChunk<R, E>, E>
        extends MpUnboundedXaddArrayQueuePad5<R, E> {

    // Must be != MpUnboundedXaddChunk.NOT_USED.
    static final long ROTATION = -2;
    static final int CACHE_LINE = 64;

    static {
        final long pIdx = MpUnboundedXaddArrayQueueProducerFields.P_INDEX_OFFSET;
        final long pChunk = MpUnboundedXaddArrayQueueProducerChunk.P_CHUNK_OFFSET;
        final long pChunkIdx = MpUnboundedXaddArrayQueueProducerChunk.P_CHUNK_INDEX_OFFSET;
        final long cIdx = MpUnboundedXaddArrayQueueConsumerFields.C_INDEX_OFFSET;
        final long cChunk = MpUnboundedXaddArrayQueueConsumerFields.C_CHUNK_OFFSET;

        checkIsolated("producerIndex", pIdx, "consumerIndex", cIdx);
        checkIsolated("producerIndex", pIdx, "consumerChunk", cChunk);
        checkIsolated("producerChunk", pChunk, "consumerIndex", cIdx);
        checkIsolated("producerChunk", pChunk, "consumerChunk", cChunk);
        checkIsolated("producerChunkIndex", pChunkIdx, "consumerIndex", cIdx);
        checkIsolated("producerChunkIndex", pChunkIdx, "consumerChunk", cChunk);
    }

    private static void checkIsolated(String a, long aOff, String b, long bOff) {
        if (Math.abs(aOff - bOff) < CACHE_LINE) {
            throw new AssertionError("MpUnboundedXaddArrayQueue layout check failed: "
                    + a + " @ " + aOff + " and " + b + " @ " + bOff
                    + " are less than " + CACHE_LINE + " bytes apart.");
        }
    }

    final int chunkMask;
    final int chunkShift;
    private final int maxPooledChunks;
    final SpscArrayQueue<R> freeChunksPool;

    MpUnboundedXaddArrayQueue(int chunkSize, int maxPooledChunks) {
        if (maxPooledChunks < 0) {
            throw new IllegalArgumentException("Expecting a non-negative maxPooledChunks, but got: " + maxPooledChunks);
        }
        chunkSize = nextPow2(Math.max(chunkSize, 1));

        this.chunkMask = chunkSize - 1;
        this.chunkShift = Integer.numberOfTrailingZeros(chunkSize);
        this.freeChunksPool = new SpscArrayQueue<>(Math.max(maxPooledChunks, 4));

        final R first = newChunk(0, null, chunkSize, maxPooledChunks > 0);
        soProducerChunk(first);
        soProducerChunkIndex(0);
        soConsumerChunk(first);

        for (int i = 1; i < maxPooledChunks; i++) {
            freeChunksPool.offer(newChunk(MpUnboundedXaddChunk.NOT_USED, null, chunkSize, true));
        }
        this.maxPooledChunks = maxPooledChunks;
    }

    private static int nextPow2(int v) {
        v--;
        v |= v >> 1; v |= v >> 2; v |= v >> 4; v |= v >> 8; v |= v >> 16;
        return v + 1;
    }

    public final int chunkSize() {
        return chunkMask + 1;
    }

    public final int maxPooledChunks() {
        return maxPooledChunks;
    }

    abstract R newChunk(long index, R prev, int chunkSize, boolean pooled);

    @Override
    public abstract boolean offer(E e);

    @Override
    public abstract E poll();

    @Override
    public abstract E peek();

    @Override
    public final Iterator<E> iterator() {
        throw new UnsupportedOperationException();
    }

    public final long currentProducerIndex() {
        return lvProducerIndex();
    }

    public final long currentConsumerIndex() {
        return lvConsumerIndex();
    }

    @Override
    public final boolean isEmpty() {
        // Order matters: load consumer before producer so a concurrent producer increment after
        // we read consumerIndex still shows up as non-empty.
        final long ci = lvConsumerIndex();
        return ci == lvProducerIndex();
    }

    @Override
    public final int size() {
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
    public final void clear() {
        while (poll() != null) {
        }
    }

    final R producerChunkForIndex(final R initialChunk, final long requiredChunkIndex) {
        R currentChunk = initialChunk;
        long jumpBackward;

        while (true) {
            if (currentChunk == null) {
                currentChunk = lvProducerChunk();
            }
            final long currentChunkIndex = currentChunk.lvIndex();
            assert currentChunkIndex != MpUnboundedXaddChunk.NOT_USED;

            jumpBackward = currentChunkIndex - requiredChunkIndex;
            if (jumpBackward >= 0) break;

            if (lvProducerChunkIndex() == currentChunkIndex) {
                currentChunk = appendNextChunks(currentChunk, currentChunkIndex, -jumpBackward);
            } else {
                currentChunk = null;
            }
        }

        for (long i = 0; i < jumpBackward; i++) {
            currentChunk = currentChunk.lvPrev();
            assert currentChunk != null;
        }
        assert currentChunk.lvIndex() == requiredChunkIndex;
        return currentChunk;
    }

    final R appendNextChunks(R currentChunk, long currentChunkIndex, long chunksToAppend) {
        assert currentChunkIndex != MpUnboundedXaddChunk.NOT_USED;

        if (!casProducerChunkIndex(currentChunkIndex, ROTATION)) {
            return null;
        }

        assert currentChunkIndex == currentChunk.lvIndex();

        for (long i = 1; i <= chunksToAppend; i++) {
            R newChunk = newOrPooledChunk(currentChunk, currentChunkIndex + i);
            soProducerChunk(newChunk);
            currentChunk.soNext(newChunk);
            currentChunk = newChunk;
        }

        soProducerChunkIndex(currentChunkIndex + chunksToAppend);
        return currentChunk;
    }

    private R newOrPooledChunk(R prevChunk, long nextChunkIndex) {
        R newChunk = freeChunksPool.poll();
        if (newChunk != null) {
            assert newChunk.lvIndex() < prevChunk.lvIndex();
            newChunk.soPrev(prevChunk);
            newChunk.soIndex(nextChunkIndex);
        } else {
            newChunk = newChunk(nextChunkIndex, prevChunk, chunkMask + 1, false);
        }
        return newChunk;
    }

    final void moveToNextConsumerChunk(R cChunk, R next) {
        cChunk.soNext(null);
        next.soPrev(null);

        if (cChunk.isPooled()) {
            final boolean pooled = freeChunksPool.offer(cChunk);
            assert pooled;
        }
        soConsumerChunk(next);
    }
}
