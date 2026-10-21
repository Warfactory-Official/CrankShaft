package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;

public final class GuestBackends {
    private static boolean available = true;
    private static boolean indirectAvailable = true;
    public static final Backend AUSM_INDIRECT = SimpleBackend.builder()
                                                        .engineFactory(level -> new EngineImpl(level,
                                                                new GuestIndirectDrawManager(), 256))
                                                        .priority(500)
                                                        .supported(
                                                                () -> available && indirectAvailable && Loader.isModLoaded(
                                                                        "ausm") && GlCompat.SUPPORTS_INDIRECT && IndirectPrograms.allLoaded() && GuestRenderPass.active() && GuestSsbos.supported())
                                                        .register(new ResourceLocation(Flywheel.ID, "ausm_indirect"));
    public static final Backend AUSM_INSTANCING = SimpleBackend.builder()
                                                          .engineFactory(
                                                                  level -> new EngineImpl(level, new GuestInstancedDrawManager(),
                                                                          256))
                                                          .priority(490)
                                                          // TODO: Gate ARB_bindless_texture and ARB_indirect_parameters variants separately in GlCompat.
                                                          .supported(() -> available && Loader.isModLoaded(
                                                                  "ausm") && GlCompat.SUPPORTS_INDIRECT && GuestRenderPass.active())
                                                          .register(
                                                                  new ResourceLocation(Flywheel.ID, "ausm_instancing"));
    public static final Backend OPTIFINE_INDIRECT = SimpleBackend.builder()
                                                                 .engineFactory(level -> new EngineImpl(level,
                                                                         new GuestIndirectDrawManager(), 256))
                                                                 .priority(500)
                                                                 .supported(
                                                                         () -> available && indirectAvailable && optifineActive() && GlCompat.SUPPORTS_INDIRECT && IndirectPrograms.allLoaded())
                                                                 .register(new ResourceLocation(Flywheel.ID,
                                                                         "optifine_indirect"));
    public static final Backend OPTIFINE_INSTANCING = SimpleBackend.builder()
                                                                   .engineFactory(level -> new EngineImpl(level,
                                                                           new GuestInstancedDrawManager(), 256))
                                                                   .priority(490)
                                                                   .supported(
                                                                           () -> available && optifineActive() && GlCompat.SUPPORTS_INDIRECT)
                                                                   .register(new ResourceLocation(Flywheel.ID,
                                                                           "optifine_instancing"));

    private GuestBackends() {
    }

    private static boolean optifineActive() {
        return !Loader.isModLoaded("ausm") && FMLClientHandler.instance().hasOptifine() && OptifineHost.active();
    }

    public static void init() {
    }

    public static void disable() {
        available = false;
    }

    public static void disableIndirect() {
        indirectAvailable = false;
    }

    public static void rearm() {
        available = true;
        indirectAvailable = true;
    }
}
