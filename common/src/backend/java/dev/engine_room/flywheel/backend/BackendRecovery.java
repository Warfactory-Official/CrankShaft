package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.backend.Backend;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Set;

// Render-thread only; reset on resource apply, retain across renderer rebuilds.
public final class BackendRecovery {
    private static final Set<Backend> REJECTED = new ReferenceOpenHashSet<>();

    private BackendRecovery() {
    }

    public static void beginReload() {
        REJECTED.clear();
    }

    public static boolean isRejected(Backend backend) {
        return REJECTED.contains(backend);
    }

    public static void reject(Backend backend, BackendUnavailableException failure) {
        REJECTED.add(backend);
        FlwBackend.LOGGER.error("Rejecting backend '{}' for this resource generation",
                Backend.REGISTRY.getIdOrThrow(backend), failure);
    }
}
