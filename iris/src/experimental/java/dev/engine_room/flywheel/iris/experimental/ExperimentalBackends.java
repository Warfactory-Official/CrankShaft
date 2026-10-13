package dev.engine_room.flywheel.iris.experimental;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.compat.CompatMod;
import dev.engine_room.flywheel.iris.engine.ExperimentalEngine;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;

public final class ExperimentalBackends {
    public static final Backend INSTANCING = SimpleBackend.builder()
                                                          .engineFactory(level -> new ExperimentalEngine(level, false))
                                                          .priority(490).supported(
                    () -> available() && !VkContext.isVulkanHost() && GlCompat.SUPPORTS_INSTANCING
                            && GlCompat.MAX_GLSL_VERSION.version >= 430
                            && (GlCompat.CAPABILITIES.OpenGL46 || GlCompat.CAPABILITIES.GL_ARB_shader_draw_parameters))
                                                          .register(ResourceUtil.rl("iris_instancing"));
    public static final Backend INDIRECT = SimpleBackend.builder()
                                                        .engineFactory(level -> new ExperimentalEngine(level, true))
                                                        .gpuDriven(GuestTerrainGate::enabled)
                                                        .priority(890).supported(
                    () -> available() && !VkContext.isVulkanHost() && GlCompat.SUPPORTS_INDIRECT)
                                                        .register(ResourceUtil.rl("iris_indirect"));
    public static final Backend VULKAN = SimpleBackend.builder()
                                                      .engineFactory(level -> new ExperimentalEngine(level, true))
                                                      .gpuDriven(GuestTerrainGate::enabled)
                                                      .priority(1890).supported(
                    () -> available() && VkContext.isVulkanHost() && VkCaps.GPU_INSTANCE_CULL)
                                                      .register(ResourceUtil.rl("iris_vk_indirect"));

    static {
        GuestTerrainGate.setShadowPass(ShadowRenderingState::areShadowsCurrentlyBeingRendered);
        GuestTerrainGate.setShadowResolution(() -> ShadowRenderer.RESOLUTION);
    }

    private ExperimentalBackends() {
    }

    private static boolean available() {
        return CompatMod.IRIS.isLoaded && FlwPrograms.SOURCES != null && Iris.isPackInUseQuick();
    }
}
