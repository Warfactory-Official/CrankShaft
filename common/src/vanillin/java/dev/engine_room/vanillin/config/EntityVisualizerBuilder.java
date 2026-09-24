package dev.engine_room.vanillin.config;

import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import dev.engine_room.flywheel.lib.visualization.SimpleEntityVisualizer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * An object to configure the visualizer for an entity.
 *
 * @param <T> The type of the entity.
 */
public final class EntityVisualizerBuilder<T extends Entity> {
    private final Configurator configurator;
    private final EntityType<T> type;
    private SimpleEntityVisualizer.@Nullable Factory<T> visualFactory;
    @Nullable
    private Predicate<T> skipVanillaRender;
    private Predicate<T> skipVanillaPrimary = entity -> false;

    public EntityVisualizerBuilder(Configurator configurator, EntityType<T> type) {
        this.configurator = configurator;
        this.type = type;
    }

    /**
     * Sets the visual factory for the entity.
     *
     * @param visualFactory The visual factory.
     * @return {@code this}
     */
    public EntityVisualizerBuilder<T> factory(SimpleEntityVisualizer.Factory<T> visualFactory) {
        this.visualFactory = visualFactory;
        return this;
    }

    /**
     * Sets a predicate to determine whether to skip rendering with the vanilla {@link EntityRenderer}.
     *
     * @param skipVanillaRender The predicate.
     * @return {@code this}
     */
    public EntityVisualizerBuilder<T> skipVanillaRender(Predicate<T> skipVanillaRender) {
        this.skipVanillaRender = skipVanillaRender;
        return this;
    }

    /**
     * Vanilla keeps rendering the entity; the predicate says when the visual owns only its primary geometry.
     *
     * @param skipVanillaPrimary The predicate.
     * @return {@code this}
     */
    public EntityVisualizerBuilder<T> skipVanillaPrimary(Predicate<T> skipVanillaPrimary) {
        this.skipVanillaRender = entity -> false;
        this.skipVanillaPrimary = skipVanillaPrimary;
        return this;
    }

    /**
     * Sets a predicate to always skip rendering with the vanilla {@link EntityRenderer}.
     *
     * @return {@code this}
     */
    public EntityVisualizerBuilder<T> neverSkipVanillaRender() {
        this.skipVanillaRender = entity -> false;
        return this;
    }

    /**
     * Constructs the entity visualizer and sets it for the entity type.
     *
     * @return The entity visualizer.
     */
    public SimpleEntityVisualizer<T> apply(boolean enabledByDefault) {
        Objects.requireNonNull(visualFactory, "Visual factory cannot be null!");
        if (skipVanillaRender == null) {
            skipVanillaRender = entity -> true;
        }

        Predicate<T> skipRender = skipVanillaRender;
        Predicate<T> skipPrimary = skipVanillaPrimary;
        if (EntityFeatureCompat.ACTIVE) {
            // Compat with Entity Texture/Model Features, Polytone: a restyled type stays vanilla.
            skipRender = skipRender.and(entity -> !EntityFeatureCompat.vanillaOwns(entity.getType()));
            skipPrimary = skipPrimary.and(entity -> !EntityFeatureCompat.vanillaOwns(entity.getType()));
        }
        SimpleEntityVisualizer<T> visualizer = new SimpleEntityVisualizer<>(visualFactory, skipRender, skipPrimary);
        configurator.register(type, visualizer, enabledByDefault);

        return visualizer;
    }
}
