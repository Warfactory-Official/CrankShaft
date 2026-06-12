package dev.engine_room.flywheel.backend;

/**
 * Sodium presence resolved WITHOUT loading any Sodium class. The Sodium-coupled {@code TerrainDrawDispatcher}
 * (its static init reads Sodium's {@code ModelQuadFacing}) must never be class-loaded when Sodium is absent -- the
 * INDIRECT backend runs standalone, so the per-frame terrain hooks guard on this. Uses a classloader-resource probe
 * rather than {@code Class.forName} (which would load, and could weave, a Sodium class), mirroring
 * {@code FlwImplMixinPlugin}; safe to reference any time from backend code.
 */
public final class SodiumClassLoadCheck {
    public static final boolean PRESENT = SodiumClassLoadCheck.class.getClassLoader()
                                                                    .getResource(
                                                                            "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;

    private SodiumClassLoadCheck() {
    }
}
