package dev.engine_room.flywheel.lib.visualization;

import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 1.12.2 substitution: {@code EntityType<T>} (1.13+) → runtime {@link Entity} class. 1.12.2 has
 * no {@code EntityType} registry equivalent; visualizers key off the runtime class.
 */
public final class SimpleEntityVisualizer<T extends Entity> implements EntityVisualizer<T> {
    private final Factory<T> visualFactory;
    private final Predicate<T> skipVanillaRender;

    public SimpleEntityVisualizer(Factory<T> visualFactory, Predicate<T> skipVanillaRender) {
        this.visualFactory = visualFactory;
        this.skipVanillaRender = skipVanillaRender;
    }

    @Override
    public EntityVisual<? super T> createVisual(VisualizationContext ctx, T entity, float partialTick) {
        return visualFactory.create(ctx, entity, partialTick);
    }

    @Override
    public boolean skipVanillaRender(T entity) {
        return skipVanillaRender.test(entity);
    }

    public static <T extends Entity> Builder<T> builder(Class<T> type) {
        return new Builder<>(type);
    }

    @FunctionalInterface
    public interface Factory<T extends Entity> {
        EntityVisual<? super T> create(VisualizationContext ctx, T entity, float partialTick);
    }

    public static final class Builder<T extends Entity> {
        private final Class<T> type;
        @Nullable
        private Factory<T> visualFactory;
        @Nullable
        private Predicate<T> skipVanillaRender;

        public Builder(Class<T> type) {
            this.type = type;
        }

        public Builder<T> factory(Factory<T> visualFactory) {
            this.visualFactory = visualFactory;
            return this;
        }

        public Builder<T> skipVanillaRender(Predicate<T> skipVanillaRender) {
            this.skipVanillaRender = skipVanillaRender;
            return this;
        }

        public Builder<T> neverSkipVanillaRender() {
            this.skipVanillaRender = entity -> false;
            return this;
        }

        public SimpleEntityVisualizer<T> apply() {
            Objects.requireNonNull(visualFactory, "Visual factory cannot be null!");
            if (skipVanillaRender == null) {
                skipVanillaRender = entity -> true;
            }

            SimpleEntityVisualizer<T> visualizer = new SimpleEntityVisualizer<>(visualFactory, skipVanillaRender);
            VisualizerRegistry.setEntityVisualizer(type, visualizer);
            return visualizer;
        }
    }
}
