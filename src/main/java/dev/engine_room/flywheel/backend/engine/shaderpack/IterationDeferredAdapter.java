package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.google.gson.JsonObject;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslPreprocessor;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import org.lwjgl.opengl.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.engine_room.flywheel.backend.engine.shaderpack.GuestPackPatches.define;
import static dev.engine_room.flywheel.backend.engine.shaderpack.GuestPackPatches.directories;

final class IterationDeferredAdapter implements DeferredOitAdapter {
    static final IterationDeferredAdapter INSTANCE = new IterationDeferredAdapter();
    private static final String MARKER = "_flw_iterationPatched";

    private IterationDeferredAdapter() {
    }

    private static void require(DeferredReplayTargets targets, int index, int format) {
        if (targets.get(index).format() != format)
            throw new UnsupportedOperationException("Iteration colortex" + index + " format changed");
    }

    @Override
    public ContractPatches.Plan stage(GuestShaderPack pack, Map<String, String> originals,
                                      JsonObject recipe) throws IOException {
        var changes = new LinkedHashMap<String, String>();
        for (var directory : directories(originals, "gbuffers_water")) {
            for (var extension : List.of(".vsh", ".fsh")) {
                var source = originals.get(directory + "gbuffers_water" + extension);
                if (source == null) throw new UnsupportedOperationException("Iteration water stage is absent");
                changes.put(directory + "clrwl_gbuffers_translucent" + extension, define(source, "PROGRAM_COLORWHEEL"));
            }
            // Capture is cleared once by the host frame hook, rather than a second begin99 dispatch.
            for (var program : Map.of("deferred98", "iteration_opaque.comp", "composite1", "iteration_layer_sort.comp",
                    "composite2", "iteration_layer_finish.comp").entrySet()) {
                var path = directory + program.getKey() + ".csh";
                if (originals.containsKey(path))
                    throw new UnsupportedOperationException("Iteration replay stage is occupied: " + path);
                changes.put(path, DeferredReplayShaders.resource(program.getValue()));
            }
            var metadata = new StringBuilder();
            for (var index : new int[]{13, 14, 18})
                metadata.append("\nconst int colortex").append(index).append("Format=RGBA16F;\nconst bool colortex")
                        .append(index).append("Clear=true;\nconst vec4 colortex").append(index)
                        .append("ClearColor=vec4(0.0);\n");
            changes.put(directory + "composite51.fsh", originals.get(directory + "composite51.fsh") + metadata);
        }
        for (var value : recipe.getAsJsonArray("programs")) {
            var program = value.getAsJsonObject();
            var target = program.get("target").getAsString();
            var vertex = program.has("vertex") ? program.get("vertex").getAsString() : "clrwl_gbuffers";
            var nativeDraw = program.has("nativeDraw") && program.get("nativeDraw").getAsBoolean();
            var fragment = GuestPackPatches.resource(program.get("fragment").getAsString());
            for (var directory : directories(originals, vertex)) {
                var source = originals.get(directory + vertex + ".vsh");
                changes.put(directory + target + ".vsh", source);
                changes.put(directory + target + ".fsh",
                        nativeDraw ? define(fragment, "CRANKSHAFT_NATIVE_ADDITIVE") : fragment);
                if (nativeDraw) {
                    changes.put(directory + target + "_color.vsh", source);
                    changes.put(directory + target + "_color.fsh",
                            define(define(fragment, "CRANKSHAFT_NATIVE_ADDITIVE"), "CRANKSHAFT_NATIVE_COLOR"));
                }
            }
        }
        changes.put("shaders.properties", originals.getOrDefault("shaders.properties", "") + """
                
                iris.features.optional=SSBO
                bufferObject.1=4 true 1.0 1.0
                bufferObject.2=2097152
                bufferObject.3=16
                bufferObject.4=4 true 0.25 0.25
                bufferObject.5=32 true 1.0 1.0
                bufferObject.6=4 true 1.0 1.0
                """);
        changes.put("colorwheel.properties",
                originals.getOrDefault("colorwheel.properties", "") + '\n' + recipe.get("properties").getAsString());
        originals.putAll(changes);
        return new ContractPatches.Plan(Map.copyOf(originals), pack.prefix(), true,
                DeferredOitProfile.ITERATION.name());
    }

    @Override
    public String transform(String name, String source, int type) {
        if (GlslAst.parse(source).has(MARKER)) return source;
        source = GlslPreprocessor.preprocess(source);
        if (name.startsWith("shadow") || name.startsWith("clrwl_shadow"))
            source = IterationDeferredPatch.shadow(source);
        if (type == GL20.GL_FRAGMENT_SHADER) {
            if (name.startsWith("clrwl_gbuffers") && !name.equals("clrwl_gbuffers_additive") && !name.equals(
                    "clrwl_gbuffers_unlit_translucent"))
                source = IterationDeferredPatch.contract(source, name.equals("clrwl_gbuffers_translucent"));
            if (name.equals("gbuffers_water") || name.equals("clrwl_gbuffers_translucent"))
                source = IterationDeferredPatch.captureMaterial(source);
            if (name.equals("clrwl_gbuffers_unlit_translucent"))
                source = IterationDeferredPatch.captureLight(source, false, false);
            if (name.equals("clrwl_gbuffers_additive"))
                source = IterationDeferredPatch.captureLight(source, true, false);
            if (name.startsWith("crankshaft_gbuffers_additive"))
                source = IterationDeferredPatch.captureLight(source, true, true);
            if (name.equals("composite50") || name.equals("composite51"))
                source = IterationDeferredPatch.resolve(source, name.equals("composite50"));
        }
        if (type == GL20.GL_FRAGMENT_SHADER || type == GL43.GL_COMPUTE_SHADER) {
            var stage = name.replaceFirst("_[a-z]$", "");
            if (stage.matches("composite[0-9]+")) {
                var index = Integer.parseInt(stage.substring(9));
                if (index >= 5 && index <= 50)
                    source = IterationDeferredPatch.guard(source, index <= 25, type == GL43.GL_COMPUTE_SHADER);
            }
        }
        return source + "\nvoid " + MARKER + "(){}\n";
    }

    @Override
    public void validate(DeferredReplayTargets targets) {
        for (var index : new int[]{9, 10, 11}) require(targets, index, GL30.GL_RGBA16F);
        require(targets, 12, GL30.GL_RG16);
        // History and material kernels share pixel coordinates with the native-resolution capture buffers.
        for (var index : new int[]{0, 1, 2, 3, 4, 5, 6, 9, 10, 11, 12}) {
            var texture = targets.get(index).main();
            if (GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_WIDTH) != targets.width()
                    || GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_HEIGHT) != targets.height())
                throw new UnsupportedOperationException(
                        "Iteration replay requires native-resolution targets: colortex" + index);
        }
        for (var name : List.of("depthtexS", "waterDepth2D", "prevDepth2D")) targets.image(name);
    }

    @Override
    public boolean capturesLights() {
        return true;
    }

    @Override
    public String clearShader() {
        return "iteration_layer_clear.comp";
    }

    @Override
    public int[] captureBindings() {
        return new int[]{1, 2, 3, 4, 5, 6};
    }

    @Override
    public DeferredReplayPlan replayPlan(DeferredReplayTargets targets, List<DeferredReplayPass> passes) {
        return IterationReplayResources.plan(targets, passes);
    }
}
