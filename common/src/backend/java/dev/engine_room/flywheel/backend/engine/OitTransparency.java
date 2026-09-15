package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;

public final class OitTransparency {
    private OitTransparency() {
    }

    public static boolean orderIndependent(Material material) {
        Transparency transparency = material.transparency();
        return transparency == Transparency.ORDER_INDEPENDENT
                || transparency == Transparency.ORDER_INDEPENDENT_ADDITIVE;
    }

    public static boolean additive(Material material) {
        return material.transparency() == Transparency.ORDER_INDEPENDENT_ADDITIVE;
    }
}
