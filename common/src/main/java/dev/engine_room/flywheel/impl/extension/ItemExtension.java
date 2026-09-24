package dev.engine_room.flywheel.impl.extension;

import dev.engine_room.flywheel.api.visualization.ItemStackVisualizer;
import org.jspecify.annotations.Nullable;

public interface ItemExtension {
    @Nullable
    ItemStackVisualizer flw$getVisualizer();

    void flw$setVisualizer(@Nullable ItemStackVisualizer visualizer);
}
