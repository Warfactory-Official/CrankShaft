package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.IrisRuntime;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

public enum DeferredOitProfile {
    SUNDIAL(new SundialDeferredAdapter()),
    ITERATION(new IterationDeferredAdapter()),
    SEUS(new SeusDeferredAdapter(false)),
    SEUS_HRR3(new SeusDeferredAdapter(true));

    private final DeferredOitAdapter adapter;
    private final boolean capturesTerrain;

    DeferredOitProfile(DeferredOitAdapter adapter) {
        this.adapter = adapter;
        capturesTerrain = adapter.capturesTerrain();
    }

    public static boolean enabled() {
        if (IrisRuntime.EXPERIMENTAL && VkContext.isVulkanHost())
            return Boolean.parseBoolean(System.getProperty("crankshaft.iris.oit.deferred", "true"));
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
        return adapter.storageProperties() + (IrisRuntime.EXPERIMENTAL ? "\nbufferObject.3 = 16\nbufferObject.4 = 256\n" : "");
    }

    public int[] storageBindings() {
        if (!IrisRuntime.EXPERIMENTAL) return adapter.storageBindings();
        return IntStream.concat(Arrays.stream(adapter.storageBindings()),
                IntStream.of(3, 4)).distinct().toArray();
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
        return this != ITERATION || !IrisRuntime.EXPERIMENTAL
                && Boolean.parseBoolean(System.getProperty("crankshaft.iris.oit.iteration", "true"));
    }

}
