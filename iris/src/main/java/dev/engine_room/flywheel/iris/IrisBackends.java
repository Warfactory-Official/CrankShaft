package dev.engine_room.flywheel.iris;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.gl.Driver;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.compat.CompatMod;
import dev.engine_room.flywheel.iris.engine.GuestEngine;
import dev.engine_room.flywheel.iris.engine.GuestIndirectDrawManager;
import dev.engine_room.flywheel.iris.engine.GuestInstancedDrawManager;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.resources.Identifier;

// Self-registers via static initializers; BackendManagerImpl.init() force-loads it by FQN. Iris classes stay behind
// the CompatMod gate: this class loads without Iris.
public final class IrisBackends {
    /**
     * Just below {@link Backends#INSTANCING}, which is unsupported while a pack is active: preferring
     * {@code flywheel:instancing} falls back here.
     */
    public static final Backend IRIS_INSTANCING = SimpleBackend.builder()
                                                               .engineFactory(level -> new GuestEngine(level,
                                                                       new GuestInstancedDrawManager(
                                                                               InstancingPrograms.get())))
                                                               .priority(490)
                                                               .supported(() -> CompatMod.IRIS.isLoaded
                                                                       && !VkContext.isVulkanHost()
                                                                       && GlCompat.SUPPORTS_INSTANCING
                                                                       && InstancingPrograms.allLoaded()
                                                                       && IrisGate.isPackInUse())
                                                               .register(Identifier.fromNamespaceAndPath(Flywheel.ID,
                                                                       "iris_instancing"));

    /**
     * Just below {@code flywheel:gl_mesh_shader} (900), which with {@link Backends#INDIRECT} is unsupported while a pack
     * is active: preferring either falls back here. GPU-driven only when the terrain guest is enabled, since that flag
     * is what the engine's terrain takeover gates on.
     */
    public static final Backend IRIS_INDIRECT = SimpleBackend.builder()
                                                             .gpuDriven(GuestTerrainGate::enabled)
                                                             .engineFactory(level -> new GuestEngine(level,
                                                                     new GuestIndirectDrawManager(
                                                                             IndirectPrograms.get())))
                                                             .priority(() -> GlCompat.DRIVER == Driver.INTEL ? 1 : 890)
                                                             .supported(() -> CompatMod.IRIS.isLoaded
                                                                     && !VkContext.isVulkanHost()
                                                                     && GlCompat.SUPPORTS_INDIRECT
                                                                     && IndirectPrograms.allLoaded()
                                                                     && IrisGate.isPackInUse())
                                                             .register(Identifier.fromNamespaceAndPath(Flywheel.ID,
                                                                     "iris_indirect"));

    static {
        // Iris re-enters Sodium's terrain draw for the shadow pass; the engine must leave that one alone.
        if (CompatMod.IRIS.isLoaded) {
            GuestTerrainGate.setShadowPass(IrisShadow::active);
            GuestTerrainGate.setShadowResolution(IrisShadow::resolution);
        }
    }

    // Separate class: this one must load without Iris, so the Iris reference resolves only once IRIS.isLoaded
    // has gated the call.
    private static final class IrisShadow {
        static boolean active() {
            return ShadowRenderingState.areShadowsCurrentlyBeingRendered();
        }

        static int resolution() {
            return ShadowRenderer.RESOLUTION;
        }
    }

    private IrisBackends() {
    }
}
