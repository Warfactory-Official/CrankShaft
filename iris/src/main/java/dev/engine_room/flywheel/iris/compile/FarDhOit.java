package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Resource-owned native-water and retained-hit producers for the same DH forward transparency graph.
 */
public final class FarDhOit implements AutoCloseable {
    private static @Nullable FarDhOit replay;
    private final FarHostOit graph;
    private final IrisLodRenderProgram[] water;
    private final GlProgram[] waterPrograms;
    private final FarLayerDhProgram[] retained;

    public FarDhOit(FarHostOit graph, IrisLodRenderProgram[] water, int[] handles, FarLayerDhProgram[] retained) {
        this.graph = graph;
        this.water = water;
        this.retained = retained;
        waterPrograms = Arrays.stream(handles).mapToObj(GlProgram::new).toArray(GlProgram[]::new);
        for (var program : waterPrograms) graph.registerSamplers(program);
    }

    public boolean ready() {
        boolean ready = graph.ready();
        for (var program : retained) ready &= program.ready();
        return ready;
    }

    public FarHostOit graph() {
        return graph;
    }

    public IrisLodRenderProgram water() {
        return water[graph.pass()];
    }

    public FarLayerDhProgram retained() {
        return retained[graph.pass()];
    }

    public void replay(Runnable draw) {
        replay = this;
        try {
            draw.run();
        } finally {
            replay = null;
        }
    }

    public static @Nullable IrisLodRenderProgram nativeProgram() {
        return replay == null ? null : replay.water();
    }

    /**
     * Native per-buffer bind/setup must restore the OIT target and read samplers before geometry resumes.
     */
    public static void bind(IrisLodRenderProgram shader) {
        if (replay != null && shader == replay.water()) {
            replay.graph.bindProgram(replay.waterPrograms[replay.graph.pass()]);
        }
    }

    @Override
    public void close() {
        for (var program : water) program.free();
        for (var program : retained) program.close();
        graph.close();
    }
}
