package dev.engine_room.flywheel.lib.compose;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import net.minecraft.entity.Entity;
import org.jspecify.annotations.Nullable;

public class ComposableEntityVisual<T extends Entity> extends AbstractVisual implements EntityVisual<T>, SimpleTickableVisual, SimpleDynamicVisual {
    private final T entity;
    private final Controller<T> controller;
    private final @Nullable Visual[] visuals;

    public ComposableEntityVisual(VisualizationContext ctx, T entity, float partialTick, Controller<T> controller) {
        super(ctx, entity.world, partialTick);
        this.entity = entity;
        this.controller = controller;
        this.visuals = new Visual[controller.elements.length];

        updateElements(partialTick);
    }

    @Override
    public void tick(TickableVisual.Context context) {
        updateElements(0.0f);

        for (var visual : visuals) {
            if (visual instanceof SimpleTickableVisual tickable) {
                tickable.tick(context);
            }
        }
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        updateElements(ctx.partialTick());

        for (var visual : visuals) {
            if (visual instanceof SimpleDynamicVisual dynamic) {
                dynamic.beginFrame(ctx);
            }
        }
    }

    private void updateElements(float partialTick) {
        if (!controller.predicate.shouldVisualize(visualizationContext, entity)) {
            for (var i = 0; i < visuals.length; i++) {
                if (visuals[i] != null) {
                    visuals[i].delete();
                    visuals[i] = null;
                }
            }
            return;
        }

        for (var i = 0; i < controller.elements.length; i++) {
            var element = controller.elements[i];
            var shouldExist = element.shouldVisualize(visualizationContext, entity);
            var exists = visuals[i] != null;
            if (shouldExist && !exists) {
                visuals[i] = element.create(visualizationContext, entity, partialTick);
            } else if (!shouldExist && exists) {
                visuals[i].delete();
                visuals[i] = null;
            }
        }
    }

    @Override
    protected void _delete() {
        for (var visual : visuals) {
            if (visual != null) {
                visual.delete();
            }
        }
    }

    public static class Controller<T> {
        private final ConfiguredElement<? super T>[] elements;
        private final VisualizationPredicate<T> predicate;

        public Controller(ConfiguredElement<? super T>[] elements, VisualizationPredicate<T> predicate) {
            this.elements = elements;
            this.predicate = predicate;
        }
    }
}
