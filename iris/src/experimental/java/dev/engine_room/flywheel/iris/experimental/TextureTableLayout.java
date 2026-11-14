package dev.engine_room.flywheel.iris.experimental;

import dev.engine_room.flywheel.iris.compile.ExperimentalBindless;

/**
 * Immutable sampler-heap decision shared by guest shader selection and pipeline-layout creation.
 */
public interface TextureTableLayout {
    boolean flywheel$supportsTextureTable();

    ExperimentalBindless.Limits flywheel$textureTableLimits();
}
