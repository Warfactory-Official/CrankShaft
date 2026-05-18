package dev.engine_room.flywheel.lib.task;

import dev.engine_room.flywheel.api.task.TaskExecutor;

import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

// Plan that exposes a CountedCompleter directly — for parallel divide-and-conquer workloads
// where ForEachSlicePlan / Distribute.tasks aren't expressive enough. Caller supplies a Factory
// that builds the root CountedCompleter; the Plan dispatches it on the executor's ForkJoinPool
// when available (compute() in-place when the calling thread is already an FJP worker, otherwise
// fjp.execute), or synchronously when the executor isn't FJP-backed.
//
// The factory's compute() should funnel any caught throwable through
// {@link TaskExecutor#recordFailure} — that sink terminates the JVM, so cooperative cancellation
// between siblings is unnecessary.
public record CountedCompleterPlan<C>(Factory<C> factory) implements SimplyComposedPlan<C> {

    public interface Factory<C> {
        CountedCompleter<?> create(TaskExecutor executor, C context, Runnable onCompletion);
    }

    public static <C> CountedCompleterPlan<C> of(Factory<C> factory) {
        return new CountedCompleterPlan<>(factory);
    }

    @Override
    public void execute(TaskExecutor executor, C context, Runnable onCompletion) {
        CountedCompleter<?> task = factory.create(executor, context, onCompletion);

        ForkJoinPool fjp = executor.forkJoinPool();
        if (fjp != null) {
            // If we're already on an FJP worker, run in-place — the worker helps execute via
            // the pool's work-stealing instead of going through a queue hop.
            if (ForkJoinTask.getPool() == fjp) {
                task.compute();
            } else {
                fjp.execute(task);
            }
        } else {
            // Non-FJP fallback (ParallelTaskExecutor without an FJP, or a unit-test direct
            // executor) — run synchronously on the calling thread.
            task.compute();
        }
    }
}
