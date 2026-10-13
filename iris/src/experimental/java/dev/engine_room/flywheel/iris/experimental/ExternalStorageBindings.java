package dev.engine_room.flywheel.iris.experimental;

import java.util.Set;

/**
 * Render-thread registration before pack-buffer lookup. Per-program slices come from {@code setUniformBuffers};
 * declarations remain visible to Iris synchronization.
 */
public interface ExternalStorageBindings {
    void flywheel$externalStorage(Set<String> names);
}
