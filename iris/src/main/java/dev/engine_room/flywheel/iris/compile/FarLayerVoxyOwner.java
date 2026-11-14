package dev.engine_room.flywheel.iris.compile;

#if MC >= 26.3

import org.jspecify.annotations.Nullable;

/**
 * Borrowed pipeline-owned layered callback; requesting it schedules compilation on the render thread.
 */
public interface FarLayerVoxyOwner {
    @Nullable FarLayerVoxyProgram flywheel$layeredFar();

    FarVoxyOit flywheel$hostOit();
}

#endif
