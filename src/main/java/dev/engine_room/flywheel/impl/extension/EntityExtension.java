package dev.engine_room.flywheel.impl.extension;

import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import org.jspecify.annotations.Nullable;

// All methods are per-class overrides injected by VisualizerTransformer: the registered visualizer
// is bound behind a per-class indy constant, so each is one virtual call with the visualizer's
// implementation inlined — no megamorphic itable hops (visualizer lookup + interface re-dispatch).
public interface EntityExtension {
    @Nullable
    EntityVisualizer<?> flw$visualizer();

    // Constant true/false: visualizer registered and enabled.
    boolean flw$canVisualize();

    // visualizer != null && visualizer.skipVanillaRender(this)
    boolean flw$skipVanillaRender();

    // visualizer == null ? null : visualizer.createVisual(ctx, this, partialTick)
    @Nullable
    EntityVisual<?> flw$createVisual(VisualizationContext ctx, float partialTick);
}
