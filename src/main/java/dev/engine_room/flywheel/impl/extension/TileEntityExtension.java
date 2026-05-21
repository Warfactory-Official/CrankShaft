package dev.engine_room.flywheel.impl.extension;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import org.jspecify.annotations.Nullable;

// See EntityExtension; BE variant.
public interface TileEntityExtension {
    @Nullable
    BlockEntityVisualizer<?> flw$visualizer();

    boolean flw$canVisualize();

    boolean flw$skipVanillaRender();

    @Nullable
    BlockEntityVisual<?> flw$createVisual(VisualizationContext ctx, float partialTick);
}
