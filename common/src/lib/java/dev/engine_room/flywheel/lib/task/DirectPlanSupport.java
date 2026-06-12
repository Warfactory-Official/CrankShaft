package dev.engine_room.flywheel.lib.task;

import dev.engine_room.flywheel.api.task.DirectPlan;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;

import java.util.List;

final class DirectPlanSupport {
    private DirectPlanSupport() {
    }

    static <C> boolean supports(Plan<C> plan) {
        return plan instanceof DirectPlan<?> direct && direct.supportsDirectExecution();
    }

    static <C> boolean all(List<Plan<C>> plans) {
        for (Plan<C> plan : plans) {
            if (!supports(plan)) {
                return false;
            }
        }
        return true;
    }

    static <C> void execute(Plan<C> plan, TaskExecutor executor, C context) {
        ((DirectPlan<C>) plan).executeDirect(executor, context);
    }
}
