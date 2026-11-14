package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import org.joml.FrustumIntersection;

/** A dynamic visual whose frame work runs only when its distance and bounds permit it. */
public interface CulledDynamicVisual extends SimpleDynamicVisual {
    boolean isVisible(FrustumIntersection frustum);

    boolean doDistanceLimitThisFrame(DynamicVisual.Context context);

    void frame(DynamicVisual.Context context);

    default void beforeFrame() {
    }

    default void afterFrame() {
    }

    @Override
    default void beginFrame(DynamicVisual.Context context) {
        beforeFrame();
        if (doDistanceLimitThisFrame(context) || !isVisible(context.frustum())) return;
        frame(context);
        afterFrame();
    }
}
