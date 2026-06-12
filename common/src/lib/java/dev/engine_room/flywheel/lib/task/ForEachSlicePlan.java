package dev.engine_room.flywheel.lib.task;

import dev.engine_room.flywheel.api.task.DirectPlan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.lib.task.functional.ConsumerWithContext;
import dev.engine_room.flywheel.lib.task.functional.SupplierWithContext;

import java.util.List;

/**
 * A plan that executes code over many slices of a provided list.
 * <p>
 * The size of the slice is dynamically determined based on the number of available threads.
 *
 * @param listSupplier A supplier of the list to iterate over.
 * @param action       The action to perform on each sub list.
 * @param <T>          The type of the list elements.
 * @param <C>          The type of the context object.
 */
public record ForEachSlicePlan<T, C>(SupplierWithContext<C, List<T>> listSupplier,
                                     ConsumerWithContext<List<T>, C> action) implements SimplyComposedPlan<C>, DirectPlan<C> {
    public static <T, C> ForEachSlicePlan<T, C> of(SupplierWithContext<C, List<T>> iterable,
                                                   ConsumerWithContext<List<T>, C> forEach) {
        return new ForEachSlicePlan<>(iterable, forEach);
    }

    public static <T, C> ForEachSlicePlan<T, C> of(SupplierWithContext<C, List<T>> iterable,
                                                   ConsumerWithContext.Ignored<List<T>, C> forEach) {
        return new ForEachSlicePlan<>(iterable, forEach);
    }

    public static <T, C> ForEachSlicePlan<T, C> of(SupplierWithContext.Ignored<C, List<T>> iterable,
                                                   ConsumerWithContext<List<T>, C> forEach) {
        return new ForEachSlicePlan<>(iterable, forEach);
    }

    public static <T, C> ForEachSlicePlan<T, C> of(SupplierWithContext.Ignored<C, List<T>> iterable,
                                                   ConsumerWithContext.Ignored<List<T>, C> forEach) {
        return new ForEachSlicePlan<>(iterable, forEach);
    }

    @Override
    public boolean supportsDirectExecution() {
        return true;
    }

    @Override
    public void executeDirect(TaskExecutor executor, C context) {
        action.accept(listSupplier.get(context), context);
    }

    @Override
    public void execute(TaskExecutor taskExecutor, C context, Runnable onCompletion) {
        taskExecutor.execute(
                () -> Distribute.slices(taskExecutor, context, onCompletion, listSupplier.get(context), action));
    }
}
