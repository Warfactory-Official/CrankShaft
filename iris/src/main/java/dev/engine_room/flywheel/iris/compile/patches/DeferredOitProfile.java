package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.engine.DeferredReplayPlan;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public enum DeferredOitProfile {
    SUNDIAL(new SundialDeferredAdapter()),
    ITERATION(new IterationDeferredAdapter());

    private final DeferredOitAdapter adapter;
    private final boolean capturesTerrain;

    DeferredOitProfile(DeferredOitAdapter adapter) {
        this.adapter = adapter;
        capturesTerrain = adapter.capturesTerrain();
    }

    public static boolean enabled() {
        var caps = GlCompat.CAPABILITIES;
        return Boolean.parseBoolean(System.getProperty("crankshaft.iris.oit.deferred", "true")) && caps != null
                && caps.glDispatchCompute != 0 && caps.glDrawElementsIndirect != 0
                && caps.glCreateBuffers != 0 && caps.glNamedBufferStorage != 0
                && caps.glCreateTextures != 0 && caps.glCopyImageSubData != 0;
    }

    public static String resource(String name) {
        try (var stream = DeferredOitProfile.class.getResourceAsStream("/assets/flywheel/iris/patches/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void stage(Path root, Set<String> present, Map<Path, String> overrides, List<AbsolutePackPath> added) {
        adapter.stage(root, present, overrides, added);
    }

    public String storageProperties() {
        return adapter.storageProperties();
    }

    public int[] storageBindings() {
        return adapter.storageBindings();
    }

    public boolean capturesTerrain() {
        return capturesTerrain;
    }

    public Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts) {
        return adapter.fragments(programs, contracts);
    }

    public void rejected(ProgramSet programs) {
        adapter.rejected(programs);
    }

    public @Nullable DeferredReplayPlan replayPlan(IrisRenderingPipeline pipeline, List<?> passes) {
        return adapter.replayPlan(pipeline, passes);
    }

    public boolean available() {
        return this != ITERATION || Boolean.parseBoolean(System.getProperty("crankshaft.iris.oit.iteration", "true"));
    }

}
