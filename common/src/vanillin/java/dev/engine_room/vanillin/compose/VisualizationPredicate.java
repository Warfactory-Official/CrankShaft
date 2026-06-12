package dev.engine_room.vanillin.compose;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;

@FunctionalInterface
public interface VisualizationPredicate<T> {
    VisualizationPredicate<?> ALWAYS_EXIST = (ctx, t) -> true;

    @SuppressWarnings("unchecked")
    static <T> VisualizationPredicate<T> alwaysTrue() {
        return (VisualizationPredicate<T>) ALWAYS_EXIST;
    }

    boolean shouldVisualize(VisualizationContext ctx, T entity);
}
