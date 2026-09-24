package dev.engine_room.flywheel.lib.visualization;

import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The standard {@link EntityVisualizer}: a visual factory + a skip-vanilla-render predicate, registered
 * onto an {@link EntityType} via the {@link Builder}. Entity sibling of {@link SimpleBlockEntityVisualizer}.
 */
public final class SimpleEntityVisualizer<T extends Entity> implements EntityVisualizer<T> {
    private final Factory<T> visualFactory;
    private final Predicate<T> skipVanillaRender;
    private final Predicate<T> skipVanillaPrimary;

    public SimpleEntityVisualizer(Factory<T> visualFactory, Predicate<T> skipVanillaRender) {
        this(visualFactory, skipVanillaRender, entity -> false);
    }

    public SimpleEntityVisualizer(Factory<T> visualFactory, Predicate<T> skipVanillaRender,
                                  Predicate<T> skipVanillaPrimary) {
        this.visualFactory = visualFactory;
        this.skipVanillaRender = skipVanillaRender;
        this.skipVanillaPrimary = skipVanillaPrimary;
    }

    /**
     * Get an object to configure the visualizer for the given entity type.
     *
     * @param type The entity type to configure.
     * @param <T>  The type of the entity.
     * @return The configuration object.
     */
    public static <T extends Entity> Builder<T> builder(EntityType<T> type) {
        return new Builder<>(type);
    }

    @Override
    public EntityVisual<? super T> createVisual(VisualizationContext ctx, T entity, float partialTick) {
        return visualFactory.create(ctx, entity, partialTick);
    }

    @Override
    public boolean skipVanillaRender(T entity) {
        return skipVanillaRender.test(entity);
    }

    /**
     * Port: whether the visual draws the vanilla renderer's primary geometry (boat hull, fishing bobber) while vanilla
     * still extracts and submits the rest. Only meaningful where {@link #skipVanillaRender} is {@code false}; read
     * once per extracted render state, render thread.
     */
    public boolean skipVanillaPrimary(T entity) {
        return skipVanillaPrimary.test(entity);
    }

    @FunctionalInterface
    public interface Factory<T extends Entity> {
        EntityVisual<? super T> create(VisualizationContext ctx, T entity, float partialTick);
    }

    /**
     * An object to configure the visualizer for an entity.
     *
     * @param <T> The type of the entity.
     */
    public static final class Builder<T extends Entity> {
        private final EntityType<T> type;
        @Nullable
        private Factory<T> visualFactory;
        @Nullable
        private Predicate<T> skipVanillaRender;

        public Builder(EntityType<T> type) {
            this.type = type;
        }

        /**
         * Sets the visual factory for the entity.
         *
         * @param visualFactory The visual factory.
         * @return {@code this}
         */
        public Builder<T> factory(Factory<T> visualFactory) {
            this.visualFactory = visualFactory;
            return this;
        }

        /**
         * Sets a predicate to determine whether to skip rendering with the vanilla {@link EntityRenderer}.
         *
         * @param skipVanillaRender The predicate.
         * @return {@code this}
         */
        public Builder<T> skipVanillaRender(Predicate<T> skipVanillaRender) {
            this.skipVanillaRender = skipVanillaRender;
            return this;
        }

        /**
         * Sets a predicate to always skip rendering with the vanilla {@link EntityRenderer}.
         *
         * @return {@code this}
         */
        public Builder<T> neverSkipVanillaRender() {
            this.skipVanillaRender = entity -> false;
            return this;
        }

        /**
         * Constructs the entity visualizer and sets it for the entity type.
         *
         * @return The entity visualizer.
         */
        public SimpleEntityVisualizer<T> apply() {
            Objects.requireNonNull(visualFactory, "Visual factory cannot be null!");
            if (skipVanillaRender == null) {
                skipVanillaRender = entity -> true;
            }

            SimpleEntityVisualizer<T> visualizer = new SimpleEntityVisualizer<>(visualFactory, skipVanillaRender);
            VisualizerRegistry.setVisualizer(type, visualizer);
            return visualizer;
        }
    }
}
