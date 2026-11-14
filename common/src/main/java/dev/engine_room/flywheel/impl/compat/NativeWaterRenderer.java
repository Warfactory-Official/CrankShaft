package dev.engine_room.flywheel.impl.compat;

import com.seibel.distanthorizons.core.render.RenderParams;

/**
 * DH renderer-local access to the native linked stages and per-frame shader state. Render thread only.
 */
public interface NativeWaterRenderer {
    boolean flywheel$waterReady();

    NativeWaterShaders flywheel$waterShaders();

    /**
     * Sets up the native uniforms while keeping every replay at the same temporal jitter sample.
     */
    void flywheel$prepareWater(RenderParams params);

    /**
     * Restores renderer-local temporal state after all producer passes.
     */
    void flywheel$finishWater();
}
