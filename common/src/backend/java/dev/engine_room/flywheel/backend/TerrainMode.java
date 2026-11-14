package dev.engine_room.flywheel.backend;

import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * How much of vanilla/Sodium's chunk terrain flywheel takes over. The single source of truth behind
 * {@link #ownsOpaque()} and {@link #compositesTranslucent()}.
 */
public enum TerrainMode implements StringRepresentable {
    /**
     * Engine renders no terrain; vanilla/Sodium draws every layer.
     */
    OFF("off"),
    /**
     * Composite only the TRANSLUCENT layer through OIT. Works on any backend, with Sodium or vanilla terrain.
     */
    TRANSLUCENT_OIT("translucent"),
    /**
     * Opaque SOLID+CUTOUT takeover ONLY: flywheel culls + draws opaque terrain, Sodium keeps the translucent
     * layer natively and no terrain rides OIT.
     */
    OPAQUE("opaque"),
    /**
     * Opaque SOLID+CUTOUT MDI takeover plus translucent OIT. Requires Sodium and a gpu-driven backend.
     */
    FULL("full");

    private final String serializedName;

    TerrainMode(String serializedName) {
        this.serializedName = serializedName;
    }

    @Nullable
    public static TerrainMode byToken(String token) {
        for (TerrainMode mode : values()) {
            if (mode.getSerializedName().equalsIgnoreCase(token)) {
                return mode;
            }
        }
        return null;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    // The opaque takeover reads Sodium's live geometry arena; without Sodium it must clamp down (see TerrainModeGate).
    public boolean requiresSodium() {
        return ownsOpaque();
    }

    /**
     * Flywheel draws the opaque (solid + cutout) layer itself via GPU-driven MDI, replacing vanilla/Sodium's.
     */
    public boolean ownsOpaque() {
        return this == OPAQUE || this == FULL;
    }

    /**
     * Flywheel composites the translucent layer through OIT so translucent instances sort against it.
     */
    public boolean compositesTranslucent() {
        return this == TRANSLUCENT_OIT || this == FULL;
    }
}
