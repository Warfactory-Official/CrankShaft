package dev.engine_room.flywheel.api.task;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@ApiStatus.NonExtendable
public interface TaskExecutor extends Executor {
    /**
     * Check for the number of threads this executor uses.
     * <br>
     * May be helpful when determining how many chunks to divide a task into.
     *
     * @return The number of threads this executor uses.
     */
    int threadCount();

    /**
     * Return the {@link ForkJoinPool} backing this executor, or {@code null} for non-FJP executors;
     * enables CountedCompleter-based plan dispatch in {@code Distribute.plans}.
     */
    default @Nullable ForkJoinPool forkJoinPool() {
        return null;
    }

    /**
     * Receives plan-execution failures; FJP-aware executors override
     * to surface throwables to the render loop.
     */
    default void recordFailure(Throwable throwable) {
    }
}
