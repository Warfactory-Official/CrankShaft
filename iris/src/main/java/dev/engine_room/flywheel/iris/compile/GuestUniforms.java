package dev.engine_room.flywheel.iris.compile;

import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;

/**
 * Pipeline-owned uniform registration and upload epochs; render thread only.
 */
public interface GuestUniforms {
    /**
     * Registers a late guest holder before Iris maps it to its owning pass.
     */
    void flywheel$registerGuestUniforms(LocationalUniformHolder holder);

    /**
     * Evaluates the uniforms registrations retained since Iris's last per-frame update. Iris's update owns every
     * other evaluation, so stateful and mod-supplied uniforms advance once per frame and never inside a shadow pass.
     */
    void flywheel$evaluateNewlyRetained();

    /**
     * Identifies each cached-uniform refresh so guests upload once for every evaluated state.
     */
    long flywheel$uniformEpoch();
}
