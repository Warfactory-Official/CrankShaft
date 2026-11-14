package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.compile.LightDemandSources;
import org.jspecify.annotations.Nullable;
import dev.engine_room.flywheel.iris.engine.DeferredOitPool;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public enum DeferredOitProfile {
    SUNDIAL(new SundialDeferredAdapter()),
    ITERATION(new IterationDeferredAdapter()),
    SEUS(new SeusDeferredAdapter(false, true)),
    SEUS_HRR3(new SeusDeferredAdapter(true, true)),
    SEUS_GFME(new SeusDeferredAdapter(false, false));

    private final DeferredOitAdapter adapter;
    private final boolean capturesTerrain;

    DeferredOitProfile(DeferredOitAdapter adapter) {
        this.adapter = adapter;
        capturesTerrain = adapter.capturesTerrain();
    }

    public static boolean enabled() {
        if (IrisRuntime.EXPERIMENTAL && VkContext.isVulkanHost())
            return true;
        var caps = GlCompat.CAPABILITIES;
        return caps != null
                && caps.glDispatchCompute != 0 && caps.glDrawElementsIndirect != 0
                && caps.glCreateBuffers != 0 && caps.glNamedBufferStorage != 0
                && caps.glCreateTextures != 0 && caps.glCopyImageSubData != 0;
    }

    /**
     * A guest light capture's in-shader snapshot test; {@code GuestShaders.Capture} sets it where early fragment tests
     * do not already cover it.
     */
    static final String CAPTURE_TEST = "const bool flw_captureTest = false;";

    /**
     * Literal stand-in for the pack's depth convention in adapter text: {@code true} when the pack takes raw reversed
     * depth natively, {@code false} under Iris's forward conversion. {@link #fragments}, {@link #lightDemand},
     * {@link DeferredHostShaders} and {@link SortedBillboardBlend#latchSource} resolve it; a source that reaches none
     * of them fails to compile.
     */
    static final String REVERSED_DEPTH = "_FLW_REVERSED_DEPTH";

    static String depthConvention(String source, boolean reversed) {
        return source.replace(REVERSED_DEPTH, Boolean.toString(reversed));
    }

    /**
     * The capture depth snapshot at {@code binding}; needs {@code screenSize} declared first.
     */
    static String snapshot(int binding) {
        return resource("capture_snapshot.glsl").replace("_FLW_SNAPSHOT_BINDING", Integer.toString(binding));
    }

    public static String resource(String name) {
        try (var stream = DeferredOitProfile.class.getResourceAsStream("/assets/flywheel/iris/patches/" + name)) {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String counts = "_FLW_CAPTURE_COUNTS_";
            int at = source.indexOf(counts);
            if (at >= 0) {
                String binding = source.substring(at + counts.length(), at + counts.length() + 1);
                source = source.replace(counts + binding,
                        resource("capture_counts.glsl").replace("_FLW_COUNTS_BINDING", binding));
            }
            if (source.contains("_FLW_LIGHT_SIMPLE"))
                source = source.replace("_FLW_LIGHT_SIMPLE", resource("light_simple.glsl")
                        .replace("_FLW_SIMPLE_HEADS",
                                name.equals("layer_light.glsl") ? "flw_lightHeads" : "flw_heads"));
            return name.equals("layer_light.glsl") || name.equals("iteration_layers.glsl")
                    ? JcppProcessor.glslPreprocessSource(source, List.of()) : source;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String independentNodeAccess(String source) {
        return Boolean.parseBoolean(System.getProperty("crankshaft.iris.relaxedLayerNodes", "true"))
                ? source.replace("coherent buffer FlwNodes", "buffer FlwNodes") : source;
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
        boolean reversed = programs.getPack().flywheel$nativeDepth();
        Map<String, String> resolved = new HashMap<>();
        adapter.fragments(programs, contracts).forEach((name, source) ->
                resolved.put(name, depthConvention(source, reversed)));
        return Map.copyOf(resolved);
    }

    public @Nullable LightDemandSources lightDemand(ProgramSet programs, Map<String, String> fragments) {
        if (this != SUNDIAL && this != ITERATION) return null;
        boolean reversed = programs.getPack().flywheel$nativeDepth();
        var sources = DeferredLightDemand.build(this == ITERATION, programs, fragments);
        return new LightDemandSources(depthConvention(sources.prepare(), reversed),
                depthConvention(sources.query(), reversed));
    }

    public static String enableLightDemand(String source) {
        return DeferredLightDemand.enable(source);
    }


    public void rejected(ProgramSet programs) {
        adapter.rejected(programs);
    }

    public DeferredOitPool.Spec pool() {
        return adapter.pool();
    }

}
