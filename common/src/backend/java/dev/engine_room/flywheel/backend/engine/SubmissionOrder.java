package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;

public final class SubmissionOrder {
    private SubmissionOrder() {
    }

    /**
     * Whether overlapping instances of the material resolve to a colour that depends on their submission order.
     */
    public static boolean matters(Material material) {
        if (!material.writeMask().color()) {
            return false;
        }
        boolean resolvedByDepth = material.writeMask().depth() && rejects(material.depthTest());
        return switch (material.transparency()) {
            case TRANSLUCENT, TRANSLUCENT_ALPHA_REPLACE -> true;
            case OPAQUE -> !resolvedByDepth;
            // Commutative blends, until a written depth rejects later instances.
            case ADDITIVE, LIGHTNING, GLINT -> resolvedByDepth;
            case CRUMBLING, ORDER_INDEPENDENT, ORDER_INDEPENDENT_ADDITIVE -> false;
        };
    }

    private static boolean rejects(DepthTest test) {
        return test != DepthTest.OFF && test != DepthTest.ALWAYS;
    }
}
