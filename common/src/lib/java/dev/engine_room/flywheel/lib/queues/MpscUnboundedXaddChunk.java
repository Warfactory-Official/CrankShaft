// SPDX-License-Identifier: Apache-2.0
package dev.engine_room.flywheel.lib.queues;

final class MpscUnboundedXaddChunk<E> extends MpUnboundedXaddChunk<MpscUnboundedXaddChunk<E>, E> {
    MpscUnboundedXaddChunk(long index, MpscUnboundedXaddChunk<E> prev, int size, boolean pooled) {
        super(index, prev, size, pooled);
    }
}
