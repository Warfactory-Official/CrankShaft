package dev.engine_room.flywheel.lib.task;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.api.task.DirectPlan;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;

import java.util.List;

public record NestedPlan<C>(List<Plan<C>> parallelPlans)
        implements SimplyComposedPlan<C>, DirectPlan<C> {
    @SafeVarargs
    public static <C> NestedPlan<C> of(Plan<C>... plans) {
        return new NestedPlan<>(ImmutableList.copyOf(plans));
    }

    @Override
    public boolean supportsDirectExecution() {
        return DirectPlanSupport.all(parallelPlans);
    }

    @Override
    public void executeDirect(TaskExecutor executor, C context) {
        for (int i = 0; i < parallelPlans.size(); i++) {
            DirectPlanSupport.execute(parallelPlans.get(i), executor, context);
        }
    }

    @Override
    public void execute(TaskExecutor taskExecutor, C context, Runnable onCompletion) {
        if (parallelPlans.isEmpty()) {
            onCompletion.run();
            return;
        }

        var size = parallelPlans.size();

        if (size == 1) {
            parallelPlans.get(0)
                    .execute(taskExecutor, context, onCompletion);
            return;
        }

        var wait = new Synchronizer(size, onCompletion);
        for (var plan : parallelPlans) {
            plan.execute(taskExecutor, context, wait);
        }
    }

    @Override
    public Plan<C> and(Plan<C> plan) {
        return new NestedPlan<>(ImmutableList.<Plan<C>>builder()
                .addAll(parallelPlans)
                .add(plan)
                .build());
    }
}
