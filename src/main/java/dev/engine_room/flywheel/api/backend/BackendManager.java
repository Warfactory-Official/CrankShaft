package dev.engine_room.flywheel.api.backend;

import dev.engine_room.flywheel.impl.BackendManagerImpl;

public final class BackendManager {
    private BackendManager() {
    }

    /**
     * Get the current backend.
     */
    public static Backend currentBackend() {
        return BackendManagerImpl.currentBackend();
    }

    public static boolean isBackendOn() {
        return BackendManagerImpl.isBackendOn();
    }

    public static Backend offBackend() {
        return BackendManagerImpl.OFF_BACKEND;
    }

    public static Backend defaultBackend() {
        return BackendManagerImpl.defaultBackend();
    }
}
