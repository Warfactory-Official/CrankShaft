package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDrawManager;
import dev.engine_room.flywheel.backend.engine.indirect.VkIndirectDrawManager;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDrawManager;
import dev.engine_room.flywheel.backend.gl.Driver;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import net.minecraft.resources.Identifier;

public final class Backends {
    /**
     * Engine origin-drift radius (blocks), shared by every backend incl. the {@code :meshlet} tiers.
     */
    public static final int MAX_ORIGIN_DISTANCE = 256;

    /**
     * Use GPU instancing to render everything.
     */
    public static final Backend INSTANCING = SimpleBackend.builder()
                                                          .engineFactory(level -> new EngineImpl(level,
                                                                  new InstancedDrawManager(InstancingPrograms.get()),
                                                                  MAX_ORIGIN_DISTANCE))
                                                          .priority(500)
                                                          // !isVulkanHost() short-circuits first so GlCompat never class-loads/inits GL on a Vulkan host.
                                                          .supported(
                                                                  () -> !VkContext.isVulkanHost() && GlCompat.SUPPORTS_INSTANCING && InstancingPrograms.allLoaded() && !ShadersModHelper.isShaderPackInUse())
                                                          .register(Identifier.fromNamespaceAndPath(Flywheel.ID,
                                                                  "instancing"));

    /**
     * Use Compute shaders to cull instances.
     */
    public static final Backend INDIRECT = SimpleBackend.builder()
                                                        .engineFactory(level -> new EngineImpl(level,
                                                                new IndirectDrawManager(IndirectPrograms.get()),
                                                                MAX_ORIGIN_DISTANCE))
                                                        // Intel has very poor performance with indirect rendering plus graphics bugs;
                                                        // demote it below INSTANCING. Read inside the supplier -- class-loading GlCompat at
                                                        // registration time would observe null GlCapabilities. On a Vulkan host the GL backends
                                                        // are unsupported, so short-circuit before touching GlCompat.
                                                        .priority(
                                                                () -> VkContext.isVulkanHost() ? 0 : (GlCompat.DRIVER == Driver.INTEL ? 1 : 1000))
                                                        .supported(
                                                                () -> !VkContext.isVulkanHost() && GlCompat.SUPPORTS_INDIRECT && IndirectPrograms.allLoaded() && !ShadersModHelper.isShaderPackInUse())
                                                        .gpuDriven(true)
                                                        .register(Identifier.fromNamespaceAndPath(Flywheel.ID,
                                                                "indirect"));

    /**
     * Use raw Vulkan compute shaders to cull instances on Minecraft's active Vulkan host.
     */
    public static final Backend VK_INDIRECT = SimpleBackend.builder()
                                                           .engineFactory(level -> new EngineImpl(level,
                                                                   new VkIndirectDrawManager(VkPrograms.get()),
                                                                   MAX_ORIGIN_DISTANCE))
                                                           .priority(2000)
                                                           .supported(
                                                                   () -> VkContext.isVulkanHost() && VkPrograms.allLoaded())
                                                           .gpuDriven(true)
                                                           .register(Identifier.fromNamespaceAndPath(Flywheel.ID,
                                                                   "vk_indirect"));

    private Backends() {
    }

    public static void init() {
    }
}
