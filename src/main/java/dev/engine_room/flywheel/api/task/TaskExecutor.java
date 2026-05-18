package dev.engine_room.flywheel.api.task;

import org.jetbrains.annotations.ApiStatus;

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
     * Return the {@link ForkJoinPool} backing this executor, or {@code null} for non-FJP
     * executors. Enables CountedCompleter-based divide-and-conquer plan dispatch in
     * {@code Distribute.plans}; returning null falls back to the queue-based path.
     */
    default ForkJoinPool forkJoinPool() {
        return null;
    }

    /**
     * Sink for failures observed during plan execution. Default is silent; FJP-aware executors
     * can override to surface throwables to the render loop.
     */
    default void recordFailure(Throwable throwable) {
    }
}
