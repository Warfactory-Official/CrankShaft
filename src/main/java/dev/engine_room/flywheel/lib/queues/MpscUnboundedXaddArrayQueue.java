// SPDX-License-Identifier: Apache-2.0
package dev.engine_room.flywheel.lib.queues;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MpscUnboundedXaddArrayQueue<E>
        extends MpUnboundedXaddArrayQueue<MpscUnboundedXaddChunk<E>, E> {

    public MpscUnboundedXaddArrayQueue(int chunkSize, int maxPooledChunks) {
        super(chunkSize, maxPooledChunks);
    }

    public MpscUnboundedXaddArrayQueue(int chunkSize) {
        this(chunkSize, 2);
    }

    @Override
    final MpscUnboundedXaddChunk<E> newChunk(long index, MpscUnboundedXaddChunk<E> prev, int chunkSize, boolean pooled) {
        return new MpscUnboundedXaddChunk<>(index, prev, chunkSize, pooled);
    }

    @Override
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException("null is reserved as the EMPTY sentinel");
        }
        final int chunkMask = this.chunkMask;
        final int chunkShift = this.chunkShift;

        final long pIndex = getAndIncrementProducerIndex();

        final int piChunkOffset = (int) (pIndex & chunkMask);
        final long piChunkIndex = pIndex >> chunkShift;

        MpscUnboundedXaddChunk<E> pChunk = lvProducerChunk();
        if (pChunk.lvIndex() != piChunkIndex) {
            // Other producers may have advanced the producer chunk as we claimed a slot in a prev
            // chunk, or we may have now stepped into a brand new chunk which needs appending.
            pChunk = producerChunkForIndex(pChunk, piChunkIndex);
        }
        pChunk.soElement(piChunkOffset, e);
        return true;
    }

    @Override
    public E poll() {
        final int chunkMask = this.chunkMask;
        final long cIndex = lpConsumerIndex();
        final int ciChunkOffset = (int) (cIndex & chunkMask);

        MpscUnboundedXaddChunk<E> cChunk = lvConsumerChunk();
        if (ciChunkOffset == 0 && cIndex != 0) {
            cChunk = pollNextBuffer(cChunk, cIndex);
            if (cChunk == null) {
                return null;
            }
        }

        E e = cChunk.lvElement(ciChunkOffset);
        if (e == null) {
            if (lvProducerIndex() == cIndex) {
                return null;
            } else {
                e = cChunk.spinForElement(ciChunkOffset, false);
            }
        }
        cChunk.soElement(ciChunkOffset, null);
        soConsumerIndex(cIndex + 1);
        return e;
    }

    @Override
    public E peek() {
        final int chunkMask = this.chunkMask;
        final long cIndex = lpConsumerIndex();
        final int ciChunkOffset = (int) (cIndex & chunkMask);

        MpscUnboundedXaddChunk<E> cChunk = lpConsumerChunk();
        if (ciChunkOffset == 0 && cIndex != 0) {
            cChunk = spinForNextIfNotEmpty(cChunk, cIndex);
            if (cChunk == null) {
                return null;
            }
        }

        E e = cChunk.lvElement(ciChunkOffset);
        if (e == null) {
            if (lvProducerIndex() == cIndex) {
                return null;
            } else {
                e = cChunk.spinForElement(ciChunkOffset, false);
            }
        }
        return e;
    }

    public E relaxedPoll() {
        final int chunkMask = this.chunkMask;
        final long cIndex = lpConsumerIndex();
        final int ciChunkOffset = (int) (cIndex & chunkMask);

        MpscUnboundedXaddChunk<E> cChunk = lpConsumerChunk();
        E e;
        if (ciChunkOffset == 0 && cIndex != 0) {
            final MpscUnboundedXaddChunk<E> next = cChunk.lvNext();
            if (next == null) return null;
            e = next.lvElement(0);
            // if the next chunk doesn't have the first element set we give up
            if (e == null) return null;
            moveToNextConsumerChunk(cChunk, next);
            cChunk = next;
        } else {
            e = cChunk.lvElement(ciChunkOffset);
            if (e == null) return null;
        }
        cChunk.soElement(ciChunkOffset, null);
        soConsumerIndex(cIndex + 1);
        return e;
    }

    public E relaxedPeek() {
        final int chunkMask = this.chunkMask;
        final long cIndex = lpConsumerIndex();
        final int cChunkOffset = (int) (cIndex & chunkMask);

        MpscUnboundedXaddChunk<E> cChunk = lpConsumerChunk();
        if (cChunkOffset == 0 && cIndex != 0) {
            cChunk = cChunk.lvNext();
            if (cChunk == null) return null;
        }
        return cChunk.lvElement(cChunkOffset);
    }

    public int drain(Consumer<E> c) {
        return drain(c, Integer.MAX_VALUE);
    }

    public int drain(Consumer<E> c, int limit) {
        if (c == null) throw new IllegalArgumentException("c is null");
        if (limit < 0) throw new IllegalArgumentException("limit is negative: " + limit);
        if (limit == 0) return 0;

        final int chunkMask = this.chunkMask;
        long cIndex = lpConsumerIndex();
        MpscUnboundedXaddChunk<E> cChunk = lpConsumerChunk();

        for (int i = 0; i < limit; i++) {
            final int consumerOffset = (int) (cIndex & chunkMask);
            E e;
            if (consumerOffset == 0 && cIndex != 0) {
                final MpscUnboundedXaddChunk<E> next = cChunk.lvNext();
                if (next == null) return i;
                e = next.lvElement(0);
                if (e == null) return i;
                moveToNextConsumerChunk(cChunk, next);
                cChunk = next;
            } else {
                e = cChunk.lvElement(consumerOffset);
                if (e == null) return i;
            }
            cChunk.soElement(consumerOffset, null);
            final long nextConsumerIndex = cIndex + 1;
            soConsumerIndex(nextConsumerIndex);
            c.accept(e);
            cIndex = nextConsumerIndex;
        }
        return limit;
    }

    public int fill(Supplier<E> s) {
        long result = 0; // long so the for-loop can safepoint check at regular intervals
        final int capacity = chunkMask + 1;
        final int offerBatch = Math.min(4096, capacity);
        do {
            final int filled = fill(s, offerBatch);
            if (filled == 0) return (int) result;
            result += filled;
        } while (result <= capacity);
        return (int) result;
    }

    public int fill(Supplier<E> s, int limit) {
        if (s == null) throw new IllegalArgumentException("supplier is null");
        if (limit < 0) throw new IllegalArgumentException("limit is negative: " + limit);
        if (limit == 0) return 0;

        final int chunkShift = this.chunkShift;
        final int chunkMask = this.chunkMask;

        long pIndex = getAndAddProducerIndex(limit);
        MpscUnboundedXaddChunk<E> pChunk = null;
        for (int i = 0; i < limit; i++) {
            final int pChunkOffset = (int) (pIndex & chunkMask);
            final long chunkIndex = pIndex >> chunkShift;
            if (pChunk == null || pChunk.lvIndex() != chunkIndex) {
                pChunk = producerChunkForIndex(pChunk, chunkIndex);
            }
            pChunk.soElement(pChunkOffset, s.get());
            pIndex++;
        }
        return limit;
    }

    private MpscUnboundedXaddChunk<E> pollNextBuffer(MpscUnboundedXaddChunk<E> cChunk, long cIndex) {
        final MpscUnboundedXaddChunk<E> next = spinForNextIfNotEmpty(cChunk, cIndex);
        if (next == null) return null;
        moveToNextConsumerChunk(cChunk, next);
        assert next.lvIndex() == (cIndex >> chunkShift);
        return next;
    }

    private MpscUnboundedXaddChunk<E> spinForNextIfNotEmpty(MpscUnboundedXaddChunk<E> cChunk, long cIndex) {
        MpscUnboundedXaddChunk<E> next = cChunk.lvNext();
        if (next == null) {
            if (lvProducerIndex() == cIndex) return null;
            final long ccChunkIndex = cChunk.lvIndex();
            if (lvProducerChunkIndex() == ccChunkIndex) {
                // no need to help too much here or the consumer latency will be hurt
                next = appendNextChunks(cChunk, ccChunkIndex, 1);
            }
            while (next == null) {
                Thread.onSpinWait();
                next = cChunk.lvNext();
            }
        }
        return next;
    }
}
