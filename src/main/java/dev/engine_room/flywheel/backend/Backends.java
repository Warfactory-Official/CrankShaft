package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDrawManager;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDrawManager;
import dev.engine_room.flywheel.backend.gl.Driver;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import net.minecraft.util.ResourceLocation;

public final class Backends {
    private static final int MAX_ORIGIN_DISTANCE = 256;

    /**
     * Use GPU instancing to render everything.
     */
    public static final Backend INSTANCING = SimpleBackend.builder()
            .engineFactory(level -> new EngineImpl(level, new InstancedDrawManager(InstancingPrograms.get()), MAX_ORIGIN_DISTANCE))
            .priority(500)
            .supported(() -> GlCompat.SUPPORTS_INSTANCING && InstancingPrograms.allLoaded())
            .register(new ResourceLocation(Flywheel.ID, "instancing"));

    /**
     * Use compute shaders to cull instances.
     */
    public static final Backend INDIRECT = SimpleBackend.builder()
            .engineFactory(level -> new EngineImpl(level, new IndirectDrawManager(IndirectPrograms.get()), MAX_ORIGIN_DISTANCE))
            // Intel has very poor performance with indirect rendering plus graphics bugs;
            // demote it below INSTANCING. Read inside the supplier — class-loading GlCompat at
            // registration time would observe null GlCapabilities.
            .priority(() -> GlCompat.DRIVER == Driver.INTEL ? 1 : 1000)
            .supported(() -> GlCompat.SUPPORTS_INDIRECT && IndirectPrograms.allLoaded())
            .register(new ResourceLocation(Flywheel.ID, "indirect"));

    private Backends() {
    }

    public static void init() {
    }
}
