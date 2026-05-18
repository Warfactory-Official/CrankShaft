package dev.engine_room.flywheel.impl.extension;

import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import org.jspecify.annotations.Nullable;

public interface EntityExtension {
    @Nullable
    EntityVisualizer<?> flw$visualizer();
}
