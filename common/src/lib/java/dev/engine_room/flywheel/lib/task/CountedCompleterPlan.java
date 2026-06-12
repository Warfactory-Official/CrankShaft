package dev.engine_room.flywheel.lib.task;

import dev.engine_room.flywheel.api.task.TaskExecutor;

import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;

public record CountedCompleterPlan<C>(Factory<C> factory) implements SimplyComposedPlan<C> {

    public static <C> CountedCompleterPlan<C> of(Factory<C> factory) {
        return new CountedCompleterPlan<>(factory);
    }

    @Override
    public void execute(TaskExecutor executor, C context, Runnable onCompletion) {
        CountedCompleter<?> task = factory.create(executor, context, onCompletion);

        ForkJoinPool fjp = executor.forkJoinPool();
        if (fjp != null) {
            Distribute.runOnFjp(fjp, task);
        } else {
            task.compute();
        }
    }

    public interface Factory<C> {
        CountedCompleter<?> create(TaskExecutor executor, C context, Runnable onCompletion);
    }
}
