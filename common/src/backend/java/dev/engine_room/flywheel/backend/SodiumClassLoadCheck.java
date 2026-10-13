package dev.engine_room.flywheel.backend;

/**
 * Backend Sodium gate, resolved without loading a Sodium class ({@code Class.forName} would load, and could weave,
 * one). Sodium-coupled classes ({@code TerrainDrawDispatcher}) MUST NOT class-load when Sodium is absent.
 */
public final class SodiumClassLoadCheck {
    public static final boolean PRESENT = SodiumClassLoadCheck.class.getClassLoader()
                                                                    .getResource(
                                                                            "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;

    private SodiumClassLoadCheck() {
    }
}
