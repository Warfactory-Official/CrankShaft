package dev.engine_room.flywheel.api.task;

/**
 * Opt-in fast-path for plans that run synchronously on the caller thread:
 * {@link #executeDirect} completes before returning. Composite plans propagate the capability by walking their children.
 */
public interface DirectPlan<C> extends Plan<C> {
    boolean supportsDirectExecution();

    void executeDirect(TaskExecutor executor, C context);
}
