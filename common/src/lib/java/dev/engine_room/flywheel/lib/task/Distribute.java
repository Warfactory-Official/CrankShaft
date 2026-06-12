package dev.engine_room.flywheel.lib.task;

import dev.engine_room.flywheel.api.task.DirectPlan;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import net.minecraft.util.Mth;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.BiConsumer;

public final class Distribute {
    // FJP work-stealing rebalances load, so leaves only need to be coarse enough to amortize
    // per-task scheduling -- not the Synchronizer's x32 over-decomposition (~8 leaves/thread).
    private static final int FJP_LEAF_DENOMINATOR = 8;

    private Distribute() {
    }

    /**
     * Distribute the given list of tasks across the threads of the task executor, balancing load
     * while keeping each runnable large enough to amortize scheduling.
     */
    public static <C, T> void tasks(TaskExecutor taskExecutor, C context, Runnable onCompletion, List<T> list,
                                    BiConsumer<T, C> action) {
        final int size = list.size();

        if (size == 0) {
            onCompletion.run();
            return;
        }

        final int sliceSize = sliceSize(taskExecutor, size);

        if (size <= sliceSize) {
            for (int i = 0; i < size; i++) {
                action.accept(list.get(i), context);
            }
            onCompletion.run();
            return;
        }

        final ForkJoinPool fjp = taskExecutor.forkJoinPool();
        if (fjp != null) {
            int fjpThreshold = Math.max(1, sliceSize(taskExecutor, size, FJP_LEAF_DENOMINATOR));
            var task = new TaskRangeTask<>(null, list, 0, size, fjpThreshold, context, action, onCompletion,
                    taskExecutor);
            runOnFjp(fjp, task);
            return;
        }

        if (sliceSize == 1) {
            var synchronizer = new Synchronizer(size, onCompletion);
            for (int i = 0; i < size; i++) {
                final T t = list.get(i);
                taskExecutor.execute(() -> {
                    action.accept(t, context);
                    synchronizer.decrementAndEventuallyRun();
                });
            }
        } else {
            var synchronizer = new Synchronizer(Mth.positiveCeilDiv(size, sliceSize), onCompletion);
            int remaining = size;

            while (remaining > 0) {
                int end = remaining;
                remaining -= sliceSize;
                int start = Math.max(remaining, 0);

                var slice = list.subList(start, end);
                taskExecutor.execute(() -> {
                    for (T t : slice) {
                        action.accept(t, context);
                    }
                    synchronizer.decrementAndEventuallyRun();
                });
            }
        }
    }

    /**
     * Distribute the given list of tasks in chunks across the threads of the task executor, letting
     * the action work on whole slices so it can share thread-local objects between elements.
     */
    public static <C, T> void slices(TaskExecutor taskExecutor, C context, Runnable onCompletion, List<T> list,
                                     BiConsumer<List<T>, C> action) {
        final int size = list.size();

        if (size == 0) {
            onCompletion.run();
            return;
        }

        final int sliceSize = sliceSize(taskExecutor, size);

        if (size <= sliceSize) {
            action.accept(list, context);
            onCompletion.run();
            return;
        }

        final ForkJoinPool fjp = taskExecutor.forkJoinPool();
        if (fjp != null) {
            int fjpThreshold = Math.max(1, sliceSize(taskExecutor, size, FJP_LEAF_DENOMINATOR));
            var task = new SliceRangeTask<>(null, list, 0, size, fjpThreshold, context, action, onCompletion,
                    taskExecutor);
            runOnFjp(fjp, task);
            return;
        }

        if (sliceSize == 1) {
            var synchronizer = new Synchronizer(size, onCompletion);
            for (int i = 0; i < size; i++) {
                final T t = list.get(i);
                taskExecutor.execute(() -> {
                    action.accept(Collections.singletonList(t), context);
                    synchronizer.decrementAndEventuallyRun();
                });
            }
        } else {
            var synchronizer = new Synchronizer(Mth.positiveCeilDiv(size, sliceSize), onCompletion);
            int remaining = size;

            while (remaining > 0) {
                int end = remaining;
                remaining -= sliceSize;
                int start = Math.max(remaining, 0);

                var subList = list.subList(start, end);
                taskExecutor.execute(() -> {
                    action.accept(subList, context);
                    synchronizer.decrementAndEventuallyRun();
                });
            }
        }
    }

    /**
     * Distribute the given list of plans across the threads of the task executor, scheduling
     * hundreds or thousands of plans in parallel batches when beneficial.
     */
    public static <C> void plans(TaskExecutor taskExecutor, C context, Runnable onCompletion, List<Plan<C>> plans) {
        final int size = plans.size();

        if (size == 0) {
            onCompletion.run();
            return;
        }

        final ForkJoinPool fjp = taskExecutor.forkJoinPool();
        if (fjp != null) {
            int threshold = Math.max(1, sliceSize(taskExecutor, size, 4));
            var task = new PlanRangeTask<>(null, plans, 0, size, threshold, context, onCompletion, taskExecutor);
            runOnFjp(fjp, task);
            return;
        }

        var synchronizer = new Synchronizer(size, onCompletion);
        final int sliceSize = sliceSize(taskExecutor, size, 8);

        if (size <= sliceSize) {
            for (int i = 0; i < size; i++) {
                executePlan(plans.get(i), taskExecutor, context, synchronizer);
            }
        } else if (sliceSize == 1) {
            for (int i = 0; i < size; i++) {
                final Plan<C> t = plans.get(i);
                taskExecutor.execute(() -> executePlan(t, taskExecutor, context, synchronizer));
            }
        } else {
            int remaining = size;

            while (remaining > 0) {
                int end = remaining;
                remaining -= sliceSize;
                int start = Math.max(remaining, 0);

                final int fStart = start;
                final int fEnd = end;
                taskExecutor.execute(() -> {
                    for (int i = fStart; i < fEnd; i++) {
                        executePlan(plans.get(i), taskExecutor, context, synchronizer);
                    }
                });
            }
        }
    }

    private static <C> void executePlan(Plan<C> plan, TaskExecutor taskExecutor, C context, Runnable onCompletion) {
        if (DirectPlanSupport.supports(plan)) {
            DirectPlanSupport.execute(plan, taskExecutor, context);
            onCompletion.run();
        } else {
            plan.execute(taskExecutor, context, onCompletion);
        }
    }

    public static int sliceSize(TaskExecutor taskExecutor, int totalSize) {
        return sliceSize(taskExecutor, totalSize, 32);
    }

    public static int sliceSize(TaskExecutor taskExecutor, int totalSize, int denominator) {
        return Mth.positiveCeilDiv(totalSize, taskExecutor.threadCount() * denominator);
    }

    static void runOnFjp(ForkJoinPool fjp, CountedCompleter<?> task) {
        // If we're already on an FJP worker, run in-place -- the worker helps execute via
        // the pool's work-stealing instead of going through a queue hop.
        if (ForkJoinTask.getPool() == fjp) {
            task.compute();
        } else {
            fjp.execute(task);
        }
    }

    private abstract static class RangeTask<C> extends CountedCompleter<Void> {
        final int startInclusive;
        final int endExclusive;
        final int threshold;
        final C context;
        final Runnable onCompletion;
        final TaskExecutor executor;

        private RangeTask(CountedCompleter<?> parent, int startInclusive, int endExclusive, int threshold,
                          C context, Runnable onCompletion, TaskExecutor executor) {
            super(parent);
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.threshold = threshold;
            this.context = context;
            this.onCompletion = onCompletion;
            this.executor = executor;
        }

        @Override
        public final void compute() {
            int start = startInclusive;
            int end = endExclusive;
            try {
                while (true) {
                    int size = end - start;
                    if (size <= threshold) {
                        runLeaf(start, end);
                        tryComplete();
                        return;
                    }
                    int mid = (start + end) >>> 1;
                    addToPendingCount(1);
                    split(mid, end).fork();
                    end = mid;
                }
            } catch (Throwable throwable) {
                executor.recordFailure(throwable);
                tryComplete();
            }
        }

        @Override
        public final void onCompletion(CountedCompleter<?> caller) {
            if (getCompleter() != null) {
                return;
            }
            onCompletion.run();
        }

        abstract void runLeaf(int start, int end);

        abstract RangeTask<C> split(int startInclusive, int endExclusive);
    }

    private static final class PlanRangeTask<C> extends RangeTask<C> {
        private final List<Plan<C>> plans;

        private PlanRangeTask(CountedCompleter<?> parent, List<Plan<C>> plans, int startInclusive, int endExclusive,
                              int threshold, C context, Runnable onCompletion, TaskExecutor executor) {
            super(parent, startInclusive, endExclusive, threshold, context, onCompletion, executor);
            this.plans = plans;
        }

        @Override
        void runLeaf(int start, int end) {
            for (int i = start; i < end; i++) {
                runPlanTyped(plans.get(i), context);
            }
        }

        @Override
        RangeTask<C> split(int startInclusive, int endExclusive) {
            return new PlanRangeTask<>(this, plans, startInclusive, endExclusive, threshold, context, onCompletion,
                    executor);
        }

        private <D> void runPlanTyped(Plan<D> plan, D planContext) {
            switch (plan) {
                case IfElsePlan<D>(var condition, var onTrue, var onFalse) ->
                        runPlanTyped(condition.getAsBoolean(planContext) ? onTrue : onFalse, planContext);
                case ConditionalPlan<D>(var condition, var onTrue) -> {
                    if (condition.getAsBoolean(planContext)) {
                        runPlanTyped(onTrue, planContext);
                    }
                }
                case MapContextPlan<D, ?> mapped -> runMappedPlan(mapped, planContext);
                case DirectPlan<D> direct when direct.supportsDirectExecution() ->
                        direct.executeDirect(executor, planContext);
                default -> {
                    addToPendingCount(1);
                    new OpaquePlanTask<>(this, plan, executor, planContext).start();
                }
            }
        }

        private <D, E> void runMappedPlan(MapContextPlan<D, E> mapped, D planContext) {
            runPlanTyped(mapped.plan(), mapped.map().get(planContext));
        }
    }

    private static final class TaskRangeTask<C, T> extends RangeTask<C> {
        private final List<T> list;
        private final BiConsumer<T, C> action;

        private TaskRangeTask(CountedCompleter<?> parent, List<T> list, int startInclusive, int endExclusive,
                              int threshold, C context, BiConsumer<T, C> action, Runnable onCompletion,
                              TaskExecutor executor) {
            super(parent, startInclusive, endExclusive, threshold, context, onCompletion, executor);
            this.list = list;
            this.action = action;
        }

        @Override
        void runLeaf(int start, int end) {
            for (int i = start; i < end; i++) {
                action.accept(list.get(i), context);
            }
        }

        @Override
        RangeTask<C> split(int startInclusive, int endExclusive) {
            return new TaskRangeTask<>(this, list, startInclusive, endExclusive, threshold, context, action,
                    onCompletion, executor);
        }
    }

    private static final class SliceRangeTask<C, T> extends RangeTask<C> {
        private final List<T> list;
        private final BiConsumer<List<T>, C> action;

        private SliceRangeTask(CountedCompleter<?> parent, List<T> list, int startInclusive, int endExclusive,
                               int threshold, C context, BiConsumer<List<T>, C> action, Runnable onCompletion,
                               TaskExecutor executor) {
            super(parent, startInclusive, endExclusive, threshold, context, onCompletion, executor);
            this.list = list;
            this.action = action;
        }

        @Override
        void runLeaf(int start, int end) {
            action.accept(list.subList(start, end), context);
        }

        @Override
        RangeTask<C> split(int startInclusive, int endExclusive) {
            return new SliceRangeTask<>(this, list, startInclusive, endExclusive, threshold, context, action,
                    onCompletion, executor);
        }
    }

    private static final class OpaquePlanTask<C> extends CountedCompleter<Void> implements Runnable {
        private final Plan<C> plan;
        private final TaskExecutor executor;
        private final C context;

        private OpaquePlanTask(CountedCompleter<?> parent, Plan<C> plan, TaskExecutor executor, C context) {
            super(parent);
            this.plan = plan;
            this.executor = executor;
            this.context = context;
        }

        private void start() {
            try {
                plan.execute(executor, context, this);
            } catch (Throwable throwable) {
                executor.recordFailure(throwable);
                tryComplete();
            }
        }

        @Override
        public void compute() {
        }

        @Override
        public void run() {
            tryComplete();
        }
    }
}
