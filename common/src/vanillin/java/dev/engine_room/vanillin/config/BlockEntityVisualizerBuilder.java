package dev.engine_room.vanillin.config;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.impl.compat.EntityFeatureCompat;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class BlockEntityVisualizerBuilder<T extends BlockEntity> {
    private final Configurator configurator;
    private final BlockEntityType<T> type;
    private SimpleBlockEntityVisualizer.@Nullable Factory<T> visualFactory;
    @Nullable
    private Predicate<T> skipVanillaRender;

    public BlockEntityVisualizerBuilder(Configurator configurator, BlockEntityType<T> type) {
        this.configurator = configurator;
        this.type = type;
    }

    /**
     * Sets the visual factory for the block entity.
     *
     * @param visualFactory The visual factory.
     * @return {@code this}
     */
    public BlockEntityVisualizerBuilder<T> factory(SimpleBlockEntityVisualizer.Factory<T> visualFactory) {
        this.visualFactory = visualFactory;
        return this;
    }

    /**
     * Sets a predicate to determine whether to skip rendering with the vanilla {@link BlockEntityRenderer}.
     *
     * @param skipVanillaRender The predicate.
     * @return {@code this}
     */
    public BlockEntityVisualizerBuilder<T> skipVanillaRender(Predicate<T> skipVanillaRender) {
        this.skipVanillaRender = skipVanillaRender;
        return this;
    }

    public BlockEntityVisualizerBuilder<T> neverSkipVanillaRender() {
        this.skipVanillaRender = blockEntity -> false;
        return this;
    }

    /**
     * Constructs the block entity visualizer and sets it for the block entity type.
     *
     * @return The block entity visualizer.
     */
    public SimpleBlockEntityVisualizer<T> apply(boolean enabledByDefault) {
        Objects.requireNonNull(visualFactory, "Visual factory cannot be null!");
        if (skipVanillaRender == null) {
            skipVanillaRender = blockEntity -> true;
        }

        SimpleBlockEntityVisualizer.Factory<T> factory = visualFactory;
        Predicate<T> skipRender = skipVanillaRender;
        if (EntityFeatureCompat.ACTIVE) {
            // Compat with Entity Model Features: a restyled type stays vanilla.
            SimpleBlockEntityVisualizer.Factory<T> visual = factory;
            factory = (ctx, blockEntity, partialTick) -> EntityFeatureCompat.vanillaOwns(type)
                    ? new VanillaOwned() : visual.create(ctx, blockEntity, partialTick);
            skipRender = skipRender.and(blockEntity -> !EntityFeatureCompat.vanillaOwns(type));
        }
        SimpleBlockEntityVisualizer<T> visualizer = new SimpleBlockEntityVisualizer<>(factory, skipRender);
        configurator.register(type, visualizer, enabledByDefault);

        return visualizer;
    }

    private static final class VanillaOwned implements BlockEntityVisual<BlockEntity> {
        @Override
        public void collectCrumblingInstances(Consumer<@Nullable Instance> consumer) {
        }

        @Override
        public void update(float partialTick) {
        }

        @Override
        public void delete() {
        }
    }
}
