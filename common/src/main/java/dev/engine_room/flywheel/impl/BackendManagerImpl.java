package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.BackendRecovery;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
#endif
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BackendManagerImpl {
    public static final Backend OFF_BACKEND = SimpleBackend.builder()
                                                           .engineFactory(_ -> {
                                                               throw new UnsupportedOperationException(
                                                                       "Cannot create engine when backend is off.");
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

    public static boolean isGpuDriven(Backend candidate) {
        return candidate.isGpuDriven();
    }

    public static boolean isGpuDriven() {
        return isGpuDriven(backend);
    }

    public static Backend defaultBackend() {
        List<Backend> sorted = backendsByPriority();
        for (Backend candidate : sorted) {
            if (candidate.automaticSelection()) return candidate;
        }
        FlwImpl.LOGGER.warn("No automatic backends registered, defaulting to 'flywheel:off'");
        return OFF_BACKEND;
    }

    public static String getBackendString() {
        return getBackendString(backend);
    }

    public static String getBackendString(Backend b) {
        Identifier id = Backend.REGISTRY.getId(b);
        return id == null ? "[unregistered]" : id.toString();
    }

    public static void init() {
        ShaderWarmup.registerRecovery(BackendManagerImpl::warmWithRecovery);
        FlwBackend.init(FlwConfig.INSTANCE.backendConfig());
        forceLoadModuleBackend("me.mlbv.meshlet.mesh.gl.MeshShaderBackends");
        forceLoadModuleBackend("me.mlbv.meshlet.mesh.vk.VkMeshShaderBackends");
        forceLoadModuleBackend("dev.engine_room.flywheel.iris.IrisBackends");
    }

    private static void forceLoadModuleBackend(String fqn) {
        try {
            Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Module backend (" + fqn + ") not on classpath", e);
        }
    }

    public static void onEndClientResourceReload(boolean didError) {
        if (didError) {
            return;
        }
        chooseBackend();
        VisualizationManagerImpl.resetAll();
    }

    public static void warmWithRecovery(Runnable warm) {
        for (; ; ) {
            try {
                warm.run();
                return;
            } catch (ProgramAvailability.Failure failure) {
                if (Boolean.getBoolean("crankshaft.shader.strict")) throw failure;
                #if MC >= 26.3
                NativeOitWarmup.invalidate();
                #endif
                if (!ProgramAvailability.allows(failure.feature())) {
                    recover(failure);
                } else {
                    ProgramAvailability.reject(failure.feature(), failure);
                    ShaderWarmup.retry(failure.feature());
                    reselect();
                }
            } catch (BackendUnavailableException failure) {
                if (Boolean.getBoolean("crankshaft.shader.strict")) throw failure;
                #if MC >= 26.3
                NativeOitWarmup.invalidate();
                #endif
                recover(failure);
            }
        }
    }

    public static void recover(BackendUnavailableException failure) {
        BackendRecovery.reject(backend, failure);
        if (VkContext.isVulkanHost()) VkPrograms.kill();
        else if (backend == Backends.INSTANCING) InstancingPrograms.kill();
        else if (backend == Backends.INDIRECT
                || Backends.GL_MESH_SHADER_ID.equals(Backend.REGISTRY.getIdOrThrow(backend))) IndirectPrograms.kill();
        reselect();
    }

    public static void reselect() {
        chooseBackend();
        VisualizationManagerImpl.resetAll();
        #if MC >= 26.3
        NativeOitWarmup.configurationChanged();
        #endif
    }

    public static void onReloadLevelRenderer(Level level) {
        chooseBackend();
        VisualizationManagerImpl.reset(level);
        #if MC >= 26.3
        NativeOitWarmup.configurationChanged();
        #endif
    }

    private static void chooseBackend() {
        boolean wasOff = backend == OFF_BACKEND;
        Backend preferred = FlwConfig.INSTANCE.backend();

        if (!BackendRecovery.isRejected(preferred) && preferred.isSupported()) {
            backend = preferred;
            if (wasOff != (backend == OFF_BACKEND)) DistantHorizonsCompat.refreshStaticContainers();
            return;
        }
        List<Backend> sorted = backendsByPriority();
        int startIndex = sorted.indexOf(preferred) + 1;
        // For safety in case we don't find anything
        backend = OFF_BACKEND;
        for (int i = startIndex; i < sorted.size(); i++) {
            Backend candidate = sorted.get(i);
            if (candidate.automaticSelection() && !BackendRecovery.isRejected(
                    candidate) && candidate.isSupported()) {
                backend = candidate;
                break;
            }
        }
        if (backend == OFF_BACKEND && (VkContext.isVulkanHost() || InstancingPrograms.allLoaded())) {
            for (Backend candidate : sorted) {
                if (candidate.automaticSelection() && !BackendRecovery.isRejected(
                        candidate) && candidate.isSupported()) {
                    backend = candidate;
                    break;
                }
            }
        }
        FlwImpl.LOGGER.warn("Flywheel backend fell back from '{}' to '{}'",
                getBackendString(preferred), getBackendString(backend));
        if (wasOff != (backend == OFF_BACKEND)) DistantHorizonsCompat.refreshStaticContainers();
    }

    // Don't store this statically because backends can theoretically change their priorities at runtime.
    private static List<Backend> backendsByPriority() {
        ArrayList<Backend> backends = new ArrayList<>(Backend.REGISTRY.getAll());
        // Sort with keys backwards so that the highest priority is first.
        backends.sort(Comparator.comparingInt(Backend::priority).reversed());
        return backends;
    }
}
