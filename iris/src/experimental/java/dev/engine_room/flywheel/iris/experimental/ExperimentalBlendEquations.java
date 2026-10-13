package dev.engine_room.flywheel.iris.experimental;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * Pack overrides supply blend factors; carrier pipelines also supply an equation.
 */
public final class ExperimentalBlendEquations {
    private ExperimentalBlendEquations() {
    }

    public static BlendFunction function(RenderPipeline pipeline, int attachment) {
        if (pipeline == null || pipeline.getColorTargetStates().isEmpty()) return null;
        var states = pipeline.getColorTargetStates();
        return states.get(Math.min(attachment, states.size() - 1)).blendFunction().orElse(null);
    }
}
