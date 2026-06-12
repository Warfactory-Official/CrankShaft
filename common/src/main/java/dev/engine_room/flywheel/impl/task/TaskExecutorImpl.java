package dev.engine_room.flywheel.impl.task;

import dev.engine_room.flywheel.api.task.TaskExecutor;

import java.util.function.BooleanSupplier;

public interface TaskExecutorImpl extends TaskExecutor {
    /**
     * Wait for running tasks until the given condition is met (equivalent to {@code syncWhile(() -> !cond.getAsBoolean())}).
     */
    boolean syncUntil(BooleanSupplier cond);

    /**
     * Wait for running tasks so long as the given condition is met (equivalent to {@code syncUntil(() -> !cond.getAsBoolean())}).
     */
    boolean syncWhile(BooleanSupplier cond);

    /**
     * Wait for all running tasks to finish; prefer {@link #syncUntil(BooleanSupplier) syncUntil} normally.
     */
    void syncPoint();

    default void wakeSync() {
    }
}
