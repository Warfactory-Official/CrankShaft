package dev.engine_room.flywheel.lib.task;

import dev.engine_room.flywheel.api.task.DirectPlan;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.lib.math.MoreMath;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.BiConsumer;

public final class Distribute {
    private Distribute() {
    }

    /**
     * Distribute the given list of tasks across the threads of the task executor.
     *
     * <p>An effort is made to balance the load across threads while also ensuring each
     * runnable passed to the executor is large enough to amortize the cost of scheduling it.</p>
     *
     * @param taskExecutor The task executor to run on.
     * @param context The context to pass to each task.
     * @param onCompletion The action to run when all tasks are complete.
     * @param list The list of objects to run tasks on.
     * @param action The action to run on each object.
     * @param <C> The context type.
     * @param <T> The object type.
     */
    public static <C, T> void tasks(TaskExecutor taskExecutor, C context, Runnable onCompletion, List<T> list, BiConsumer<T, C> action) {
        final int size = list.size();

        if (size == 0) {
            onCompletion.run();
            return;
        }

        final int sliceSize = sliceSize(taskExecutor, size);

        if (size <= sliceSize) {
            // Index loop avoids Iterator allocation; ArrayList.get(i) is O(1) and JIT-folds cleanly.
            for (int i = 0; i < size; i++) {
                action.accept(list.get(i), context);
            }
            onCompletion.run();
            return;
        }

        // FJP fast-path: CountedCompleter divide-and-conquer instead of a shared Synchronizer.
        // The Synchronizer path contends hard on a single AtomicInteger.decrementAndGet when
        // many workers complete short tasks in burst (e.g. LightUpdatedVisualStorage dispatching
        // hundreds of per-visual updateLight calls per OF/CDL update). CountedCompleter
        // propagates per-node pending counts up a tree, so siblings don't fight for one cache line.
        final ForkJoinPool fjp = taskExecutor.forkJoinPool();
        if (fjp != null) {
            var task = new TaskRangeTask<>(null, list, 0, size, sliceSize, context, action, onCompletion, taskExecutor);
            if (ForkJoinTask.getPool() == fjp) {
                task.compute();
            } else {
                fjp.execute(task);
            }
            return;
        }

        // Non-FJP fallback (ParallelTaskExecutor or unit-test direct executor) keeps the original
        // Synchronizer dispatch.
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
            var synchronizer = new Synchronizer(MoreMath.ceilingDiv(size, sliceSize), onCompletion);
            int remaining = size;

            while (remaining > 0) {
                int end = remaining;
                remaining -= sliceSize;
                int start = Math.max(remaining, 0);

                // subList + enhanced-for matches slices()'s pattern and stays O(n) total across
                // workers regardless of List impl. Per-slice Iterator allocation is negligible
                // vs the per-element action cost.
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
     * Distribute the given list of tasks in chunks across the threads of the task executor.
     *
     * <p>Unlike {@link #tasks(TaskExecutor, Object, Runnable, List, BiConsumer)}, this method
     * gives the action a list of objects to work on, rather than a single object. This may be handy
     * for when you can share some thread local objects between individual elements of the list.</p>
     *
     * <p>An effort is made to balance the load across threads while also ensuring each
     * runnable passed to the executor is large enough to amortize the cost of scheduling it.</p>
     *
     * @param taskExecutor The task executor to run on.
     * @param context The context to pass to each task.
     * @param onCompletion The action to run when all tasks are complete.
     * @param list The list of objects to run tasks on.
     * @param action The action to run on each slice.
     * @param <C> The context type.
     * @param <T> The object type.
     */
    public static <C, T> void slices(TaskExecutor taskExecutor, C context, Runnable onCompletion, List<T> list, BiConsumer<List<T>, C> action) {
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

        // FJP fast-path: CountedCompleter divide-and-conquer (see tasks() for the rationale).
        final ForkJoinPool fjp = taskExecutor.forkJoinPool();
        if (fjp != null) {
            var task = new SliceRangeTask<>(null, list, 0, size, sliceSize, context, action, onCompletion, taskExecutor);
            if (ForkJoinTask.getPool() == fjp) {
                task.compute();
            } else {
                fjp.execute(task);
            }
            return;
        }

        // Non-FJP fallback.
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
            var synchronizer = new Synchronizer(MoreMath.ceilingDiv(size, sliceSize), onCompletion);
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
     * Distribute the given list of plans across the threads of the task executor.
     *
     * <p>Plan scheduling is normally lightweight compared to the cost of execution,
     * but when many hundreds or thousands of plans need to be scheduled it may be beneficial
     * to parallelize. This method does exactly that, distributing larger chunks of plans to
     * be scheduled in batches.</p>
     *
     * <p>An effort is made to balance the load across threads while also ensuring each
     * runnable passed to the executor is large enough to amortize the cost of scheduling it.</p>
     *
     * @param taskExecutor The task executor to run on.
     * @param context The context to pass to the plans.
     * @param onCompletion The action to run when all plans are complete.
     * @param plans The list of plans to execute.
     * @param <C> The context type.
     */
    public static <C> void plans(TaskExecutor taskExecutor, C context, Runnable onCompletion, List<Plan<C>> plans) {
        final int size = plans.size();

        if (size == 0) {
            onCompletion.run();
            return;
        }

        // FJP fast-path: CountedCompleter divide-and-conquer avoids ConcurrentLinkedDeque overhead
        // for inner splits. Falls through when the executor isn't FJP-backed (forkJoinPool() == null).
        final ForkJoinPool fjp = taskExecutor.forkJoinPool();
        if (fjp != null) {
            int threshold = Math.max(1, sliceSize(taskExecutor, size, 4));
            var task = new PlanRangeTask<>(null, plans, 0, size, threshold, context, onCompletion, taskExecutor);
            if (ForkJoinTask.getPool() == fjp) {
                task.compute();
            } else {
                fjp.execute(task);
            }
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
        return MoreMath.ceilingDiv(totalSize, taskExecutor.threadCount() * denominator);
    }

    private static final class PlanRangeTask<C> extends CountedCompleter<Void> {
        private final List<Plan<C>> plans;
        private final int startInclusive;
        private final int endExclusive;
        private final int threshold;
        private final C context;
        private final Runnable onCompletion;
        private final TaskExecutor executor;

        private PlanRangeTask(CountedCompleter<?> parent, List<Plan<C>> plans, int startInclusive, int endExclusive, int threshold, C context, Runnable onCompletion, TaskExecutor executor) {
            super(parent);
            this.plans = plans;
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.threshold = threshold;
            this.context = context;
            this.onCompletion = onCompletion;
            this.executor = executor;
        }

        @Override
        public void compute() {
            int start = startInclusive;
            int end = endExclusive;
            try {
                while (true) {
                    int size = end - start;
                    if (size <= threshold) {
                        runPlans(start, end);
                        tryComplete();
                        return;
                    }
                    int mid = (start + end) >>> 1;
                    addToPendingCount(1);
                    new PlanRangeTask<>(this, plans, mid, end, threshold, context, onCompletion, executor).fork();
                    end = mid;
                }
            } catch (Throwable throwable) {
                executor.recordFailure(throwable);
                tryComplete();
            }
        }

        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            if (getCompleter() != null) {
                return;
            }
            onCompletion.run();
        }

        private void runPlans(int start, int end) {
            for (int i = start; i < end; i++) {
                runPlanTyped(plans.get(i), context);
            }
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

    // Divide-and-conquer per-item dispatch for tasks(). Mirrors PlanRangeTask: split the range
    // in half, fork the right child, continue with the left until size <= threshold. CountedCompleter
    // propagates completion through per-node pending counts, avoiding the shared
    // AtomicInteger.decrementAndGet contention that Synchronizer hit when many short tasks burst.
    private static final class TaskRangeTask<C, T> extends CountedCompleter<Void> {
        private final List<T> list;
        private final int startInclusive;
        private final int endExclusive;
        private final int threshold;
        private final C context;
        private final BiConsumer<T, C> action;
        private final Runnable onCompletion;
        private final TaskExecutor executor;

        private TaskRangeTask(CountedCompleter<?> parent, List<T> list, int startInclusive, int endExclusive, int threshold, C context, BiConsumer<T, C> action, Runnable onCompletion, TaskExecutor executor) {
            super(parent);
            this.list = list;
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.threshold = threshold;
            this.context = context;
            this.action = action;
            this.onCompletion = onCompletion;
            this.executor = executor;
        }

        @Override
        public void compute() {
            int start = startInclusive;
            int end = endExclusive;
            try {
                while (true) {
                    int size = end - start;
                    if (size <= threshold) {
                        for (int i = start; i < end; i++) {
                            action.accept(list.get(i), context);
                        }
                        tryComplete();
                        return;
                    }
                    int mid = (start + end) >>> 1;
                    addToPendingCount(1);
                    new TaskRangeTask<>(this, list, mid, end, threshold, context, action, onCompletion, executor).fork();
                    end = mid;
                }
            } catch (Throwable throwable) {
                executor.recordFailure(throwable);
                tryComplete();
            }
        }

        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            if (getCompleter() != null) {
                return;
            }
            onCompletion.run();
        }
    }

    // Slice-granularity twin of TaskRangeTask: the action receives the entire leaf range as a
    // subList instead of per-item dispatch. Same divide-and-conquer skeleton.
    private static final class SliceRangeTask<C, T> extends CountedCompleter<Void> {
        private final List<T> list;
        private final int startInclusive;
        private final int endExclusive;
        private final int threshold;
        private final C context;
        private final BiConsumer<List<T>, C> action;
        private final Runnable onCompletion;
        private final TaskExecutor executor;

        private SliceRangeTask(CountedCompleter<?> parent, List<T> list, int startInclusive, int endExclusive, int threshold, C context, BiConsumer<List<T>, C> action, Runnable onCompletion, TaskExecutor executor) {
            super(parent);
            this.list = list;
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.threshold = threshold;
            this.context = context;
            this.action = action;
            this.onCompletion = onCompletion;
            this.executor = executor;
        }

        @Override
        public void compute() {
            int start = startInclusive;
            int end = endExclusive;
            try {
                while (true) {
                    int size = end - start;
                    if (size <= threshold) {
                        action.accept(list.subList(start, end), context);
                        tryComplete();
                        return;
                    }
                    int mid = (start + end) >>> 1;
                    addToPendingCount(1);
                    new SliceRangeTask<>(this, list, mid, end, threshold, context, action, onCompletion, executor).fork();
                    end = mid;
                }
            } catch (Throwable throwable) {
                executor.recordFailure(throwable);
                tryComplete();
            }
        }

        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            if (getCompleter() != null) {
                return;
            }
            onCompletion.run();
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
