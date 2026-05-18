package dev.engine_room.flywheel.api.visualization;

import org.jetbrains.annotations.ApiStatus;

/**
 * {@code queueAdd} / {@code queueRemove} / {@code queueUpdate} must be called from the main
 * (client) thread — the default transaction queue is SPSC. For worker-thread producers, override
 * {@link dev.engine_room.flywheel.impl.visualization.storage.Storage#getTransactionQueue} to
 * return an MPSC variant.
 */
@ApiStatus.NonExtendable
public interface VisualManager<T> {
    /**
     * Get the number of game objects that are currently being visualized.
     *
     * @return The visual count.
     */
    int visualCount();

    void queueAdd(T obj);

    void queueRemove(T obj);

    void queueUpdate(T obj);
}
