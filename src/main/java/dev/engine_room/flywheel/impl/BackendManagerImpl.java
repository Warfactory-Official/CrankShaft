package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BackendManagerImpl {
    public static final Backend OFF_BACKEND = SimpleBackend.builder()
            .engineFactory(level -> {
                throw new UnsupportedOperationException("Cannot create engine when backend is off.");
            })
            .supported(() -> true)
            .register(ResourceUtil.rl("off"));

    private static Backend backend = OFF_BACKEND;

    private BackendManagerImpl() {
    }

    public static Backend currentBackend() {
        return backend;
    }

    public static boolean isBackendOn() {
        return backend != OFF_BACKEND;
    }

    public static Backend defaultBackend() {
        List<Backend> sorted = backendsByPriority();
        if (sorted.isEmpty()) {
            // This probably shouldn't happen, but fail gracefully.
            FlwImpl.LOGGER.warn("No backends registered, defaulting to 'flywheel:off'");
            return OFF_BACKEND;
        }
        return sorted.get(0);
    }

    public static String getBackendString() {
        return getBackendString(backend);
    }

    public static String getBackendString(Backend b) {
        ResourceLocation id = Backend.REGISTRY.getId(b);
        return id == null ? "[unregistered]" : id.toString();
    }

    public static void init() {
        // 1.12.2: touch Backends so INDIRECT/INSTANCING register before the first chooseBackend() pass.
        Backends.init();
    }

    public static void onEndClientResourceReload(boolean didError) {
        if (didError) {
            return;
        }
        chooseBackend();
        VisualizationManagerImpl.resetAll();
    }

    public static void onReloadLevelRenderer(World level) {
        chooseBackend();
        VisualizationManagerImpl.reset(level);
    }

    private static void chooseBackend() {
        Backend preferred = FlwConfig.INSTANCE.backend();
        if (preferred.isSupported()) {
            backend = preferred;
            return;
        }
        List<Backend> sorted = backendsByPriority();
        int startIndex = sorted.indexOf(preferred) + 1;
        // For safety in case we don't find anything
        backend = OFF_BACKEND;
        for (int i = startIndex; i < sorted.size(); i++) {
            Backend candidate = sorted.get(i);
            if (candidate.isSupported()) {
                backend = candidate;
                break;
            }
        }
        FlwImpl.LOGGER.warn("Flywheel backend fell back from '{}' to '{}'",
                getBackendString(preferred), getBackendString(backend));
    }

    // Don't store this statically because backends can theoretically change their priorities at runtime.
    private static List<Backend> backendsByPriority() {
        ArrayList<Backend> backends = new ArrayList<>(Backend.REGISTRY.getAll());
        // Sort with keys backwards so that the highest priority is first.
        backends.sort(Comparator.comparingInt(Backend::priority).reversed());
        return backends;
    }
}
