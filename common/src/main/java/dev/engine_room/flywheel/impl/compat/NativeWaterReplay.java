package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.backend.engine.indirect.RawOitPass;

/**
 * Native LoD translucent draws, preflighted before their host relinquishes its ordinary colour pass.
 */
public interface NativeWaterReplay {
    /**
     * Requests every shader needed by the selected OIT graph; called on the render thread before ownership changes.
     */
    boolean ready();

    /**
     * Replays the original host draw commands into one producer pass, preserving native material shading.
     */
    void draw(RawOitPass pass);
}
