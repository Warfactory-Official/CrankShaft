package dev.engine_room.flywheel.lib.visualization;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.tileentity.TileEntity;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 1.12.2 substitution: {@code BlockEntityType<T>} (1.13+) → runtime {@link TileEntity} class.
 */
public final class SimpleBlockEntityVisualizer<T extends TileEntity> implements BlockEntityVisualizer<T> {
    private final Factory<T> visualFactory;
    private final Predicate<T> skipVanillaRender;

    public SimpleBlockEntityVisualizer(Factory<T> visualFactory, Predicate<T> skipVanillaRender) {
        this.visualFactory = visualFactory;
        this.skipVanillaRender = skipVanillaRender;
    }

    @Override
    public BlockEntityVisual<? super T> createVisual(VisualizationContext ctx, T blockEntity, float partialTick) {
        return visualFactory.create(ctx, blockEntity, partialTick);
    }

    @Override
    public boolean skipVanillaRender(T blockEntity) {
        return skipVanillaRender.test(blockEntity);
    }

    public static <T extends TileEntity> Builder<T> builder(Class<T> type) {
        return new Builder<>(type);
    }

    @FunctionalInterface
    public interface Factory<T extends TileEntity> {
        BlockEntityVisual<? super T> create(VisualizationContext ctx, T blockEntity, float partialTick);
    }

    public static final class Builder<T extends TileEntity> {
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
            this.skipVanillaRender = blockEntity -> false;
            return this;
        }

        public SimpleBlockEntityVisualizer<T> apply() {
            Objects.requireNonNull(visualFactory, "Visual factory cannot be null!");
            if (skipVanillaRender == null) {
                skipVanillaRender = blockEntity -> true;
            }

            SimpleBlockEntityVisualizer<T> visualizer = new SimpleBlockEntityVisualizer<>(visualFactory, skipVanillaRender);
            VisualizerRegistry.setBlockEntityVisualizer(type, visualizer);
            return visualizer;
        }
    }
}
