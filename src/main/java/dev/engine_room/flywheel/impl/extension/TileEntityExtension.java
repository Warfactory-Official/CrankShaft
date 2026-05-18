package dev.engine_room.flywheel.impl.extension;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import org.jspecify.annotations.Nullable;

public interface TileEntityExtension {
    @Nullable
    BlockEntityVisualizer<?> flw$visualizer();
}
