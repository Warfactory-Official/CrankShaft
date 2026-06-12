package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TerrainModeGate {
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private TerrainModeGate() {
    }

    public static TerrainMode effective(TerrainMode requested) {
        if (requested.requiresSodium() && !SodiumCompat.isSodiumActive()) {
            TerrainMode fallback = requested == TerrainMode.OPAQUE ? TerrainMode.OFF : TerrainMode.TRANSLUCENT_OIT;
            if (WARNED.compareAndSet(false, true)) {
                FlwImpl.CONFIG_LOGGER.error(
                        "terrain={} requires Sodium (opaque takeover reads Sodium's live geometry arena); "
                                + "Sodium is absent -- falling back to {}.",
                        requested.token(), fallback.token());
            }
            return fallback;
        }
        return requested;
    }
}
