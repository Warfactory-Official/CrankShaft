package dev.engine_room.flywheel.lib.task;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.api.task.DirectPlan;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.lib.task.functional.RunnableWithContext;

import java.util.List;

public record SimplePlan<C>(List<RunnableWithContext<C>> parallelTasks)
        implements SimplyComposedPlan<C>, DirectPlan<C> {
    @SafeVarargs
    public static <C> SimplePlan<C> of(RunnableWithContext.Ignored<C>... tasks) {
        return new SimplePlan<>(List.of(tasks));
    }

    @SafeVarargs
    public static <C> SimplePlan<C> of(RunnableWithContext<C>... tasks) {
        return new SimplePlan<>(List.of(tasks));
    }

    public static <C> SimplePlan<C> of(List<RunnableWithContext<C>> tasks) {
        return new SimplePlan<>(tasks);
    }

    @Override
    public boolean supportsDirectExecution() {
        return true;
    }

    @Override
    public void executeDirect(TaskExecutor executor, C context) {
        for (RunnableWithContext<C> parallelTask : parallelTasks) {
            parallelTask.run(context);
        }
    }

    @Override
    public void execute(TaskExecutor taskExecutor, C context, Runnable onCompletion) {
        if (parallelTasks.isEmpty()) {
            onCompletion.run();
            return;
        }

        taskExecutor.execute(() -> Distribute.tasks(taskExecutor, context, onCompletion, parallelTasks, RunnableWithContext::run));
    }

    @Override
    public Plan<C> and(Plan<C> plan) {
        if (plan instanceof SimplePlan<C> simple) {
            return of(ImmutableList.<RunnableWithContext<C>>builder()
                    .addAll(parallelTasks)
                    .addAll(simple.parallelTasks)
                    .build());
        }
        return SimplyComposedPlan.super.and(plan);
    }
}
