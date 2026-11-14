package dev.engine_room.flywheel.lib.util;

import dev.engine_room.flywheel.api.material.TerrainOverlay;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-thread registrations. Remove an overlay before releasing any texture or uniform buffer it owns.
 */
public final class TerrainOverlays {
    private static final Map<ClientLevel, State> LEVELS = new IdentityHashMap<>();
    private static long revision;

    private TerrainOverlays() {
    }

    public static void add(ClientLevel level, TerrainOverlay overlay) {
        var state = LEVELS.computeIfAbsent(level, _ -> new State());
        if (state.overlays.stream().anyMatch(value -> value.fragmentSource().equals(overlay.fragmentSource())))
            throw new IllegalStateException(
                    "Terrain overlay source is already registered: " + overlay.fragmentSource());
        state.overlays.add(overlay);
        state.registrations.add(new Registration(overlay));
        revision++;
    }

    public static void remove(ClientLevel level, TerrainOverlay overlay) {
        var state = LEVELS.get(level);
        if (state == null) return;
        var index = state.overlays.indexOf(overlay);
        if (index < 0) return;
        state.registrations.remove(index).registered = false;
        state.overlays.remove(index);
        if (state.overlays.isEmpty()) LEVELS.remove(level);
        revision++;
    }

    /**
     * Borrowed until the next registration change; callers must not modify the list.
     */
    public static List<TerrainOverlay> get(ClientLevel level) {
        var state = LEVELS.get(level);
        return state == null ? List.of() : state.overlays;
    }

    /**
     * Render thread; changes on every successful add or removal, including another level's registrations.
     */
    public static long revision() {
        return revision;
    }

    /**
     * Borrowed until the next registration change. Re-registering an overlay creates a distinct entry.
     */
    public static List<Registration> registrations(ClientLevel level) {
        var state = LEVELS.get(level);
        return state == null ? List.of() : state.registrations;
    }

    /**
     * Render-thread readers check {@link #isRegistered()} before consuming the overlay's resources.
     */
    public static final class Registration {
        private final TerrainOverlay overlay;
        private boolean registered = true;

        private Registration(TerrainOverlay overlay) {
            this.overlay = overlay;
        }

        public TerrainOverlay overlay() {
            return overlay;
        }

        /**
         * Render thread; removal invalidates this entry before the caller can release the overlay's resources.
         */
        public boolean isRegistered() {
            return registered;
        }
    }

    private static final class State {
        private final List<TerrainOverlay> overlays = new ArrayList<>();
        private final List<Registration> registrations = new ArrayList<>();
    }
}
