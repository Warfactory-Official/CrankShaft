package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.google.gson.JsonObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static dev.engine_room.flywheel.backend.engine.shaderpack.GuestPackPatches.define;
import static dev.engine_room.flywheel.backend.engine.shaderpack.GuestPackPatches.directories;

final class SundialDeferredAdapter implements DeferredOitAdapter {
    static final SundialDeferredAdapter INSTANCE = new SundialDeferredAdapter();
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\h*#\\h*include\\h+\"([^\"]+)\"");

    private SundialDeferredAdapter() {
    }

    private static String lightTargets(String source) {
        // 1.12.2: native Sundial uses 0..7; AUSM's spare 9/10 carry the contract's extra light targets.
        return define(define(source, "colortex15 colortex10"), "colortex14 colortex9")
                     .replace("RENDERTARGETS: 15", "RENDERTARGETS: 10")
                     .replace("RENDERTARGETS: 14", "RENDERTARGETS: 9");
    }

    private static String read(Map<String, String> originals, Map<String, String> changes, String path) {
        var source = changes.containsKey(path) ? changes.get(path) : originals.get(path);
        if (source == null) throw new UnsupportedOperationException("Sundial wrapper source is absent: " + path);
        return source;
    }

    private static void requireInclude(String source, String expected) {
        var includes = INCLUDE.matcher(source).results().map(result -> result.group(1)).toList();
        if (!includes.equals(List.of(expected)))
            throw new UnsupportedOperationException("Sundial wrapper includes changed: " + expected);
    }

    private static void require(DeferredReplayTargets targets, int index, int format) {
        if (targets.get(index).format() != format)
            throw new UnsupportedOperationException("Sundial colortex" + index + " format changed");
    }

    @Override
    public ContractPatches.Plan stage(GuestShaderPack pack, Map<String, String> originals,
                                      JsonObject recipe) throws IOException {
        var changes = new LinkedHashMap<String, String>();
        for (var value : recipe.getAsJsonArray("wrappers")) {
            var wrapper = value.getAsJsonObject();
            var target = wrapper.get("target").getAsString();
            var shadow = target.startsWith("clrwl_shadow");
            var base = shadow ? "clrwl_shadow" : "clrwl_gbuffers";
            for (var directory : directories(originals, base)) {
                var nativeName = shadow ? originals.containsKey(
                        directory + "shadow_water.fsh") ? "shadow_water" : "shadow" : "gbuffers_water";
                if (!originals.containsKey(directory + nativeName + ".vsh") || !originals.containsKey(
                        directory + nativeName + ".fsh")) continue;
                for (var extension : List.of(".vsh", ".fsh")) {
                    var contract = read(originals, changes, directory + base + extension);
                    var nativeSource = read(originals, changes, directory + nativeName + extension);
                    var vertex = extension.equals(".vsh");
                    requireInclude(contract, wrapper.get(vertex ? "vertexContract" : "fragmentContract").getAsString());
                    requireInclude(nativeSource, wrapper.get(vertex ? "vertexNative" : "fragmentNative").getAsString());
                    changes.put(directory + target + extension,
                            (wrapper.get("useContract").getAsBoolean() ? contract : nativeSource)
                                    + "\nvoid " + wrapper.get("marker").getAsString() + "(){}\n");
                }
            }
        }
        var nativeProperties = new StringBuilder();
        for (var value : recipe.getAsJsonArray("programs")) {
            var program = value.getAsJsonObject();
            var target = program.get("target").getAsString();
            var vertex = program.has("vertex") ? program.get("vertex").getAsString() : "clrwl_gbuffers";
            var nativeDraw = program.has("nativeDraw") && program.get("nativeDraw").getAsBoolean();
            var fragment = lightTargets(GuestPackPatches.resource(program.get("fragment").getAsString()));
            // Native directive scans exclude virtual contracts; the native beam wrapper declares both light targets.
            for (var lightIndex : new int[]{9, 10})
                fragment += "\nconst int colortex" + lightIndex + "Format=RGBA16F;\nconst bool colortex" + lightIndex
                        + "Clear=true;\nconst vec4 colortex" + lightIndex + "ClearColor=vec4(0.0);\n";
            for (var directory : directories(originals, vertex)) {
                var source = read(originals, changes, directory + vertex + ".vsh");
                changes.put(directory + target + ".vsh", source);
                changes.put(directory + target + ".fsh",
                        nativeDraw ? define(fragment, "CRANKSHAFT_NATIVE_ADDITIVE") : fragment);
                if (nativeDraw) {
                    changes.put(directory + vertex + ".fsh", define(fragment, "CRANKSHAFT_NATIVE_ADDITIVE"));
                    changes.put(directory + target + "_color.vsh", source);
                    changes.put(directory + target + "_color.fsh",
                            define(define(fragment, "CRANKSHAFT_NATIVE_ADDITIVE"), "CRANKSHAFT_NATIVE_COLOR"));
                    nativeProperties.append("\nblend.").append(vertex).append("=ONE ONE ZERO ONE\nalphaTest.")
                                    .append(vertex).append("=off\n");
                }
            }
        }
        changes.put("shaders.properties", originals.getOrDefault("shaders.properties", "") + nativeProperties + """
                
                iris.features.optional=SSBO
                uniform.vec2.screenSize=vec2(viewWidth,viewHeight)
                bufferObject.0=4 true 1.0 1.0
                bufferObject.1=2097152
                bufferObject.2=16
                bufferObject.5=4 true 1.0 1.0
                """);
        changes.put("colorwheel.properties",
                originals.getOrDefault("colorwheel.properties", "") + '\n' + recipe.get("properties").getAsString());
        originals.putAll(changes);
        return new ContractPatches.Plan(Map.copyOf(originals), pack.prefix(),
                recipe.has("emissiveLight") && recipe.get("emissiveLight").getAsBoolean(),
                DeferredOitProfile.SUNDIAL.name());
    }

    @Override
    public String transform(String name, String source, int type) {
        if (type != GL20.GL_FRAGMENT_SHADER) return source;
        if (name.startsWith("clrwl_gbuffers")) source = SundialDeferredPatch.opaqueContract(source);
        if (name.equals("gbuffers_water") || name.equals("clrwl_gbuffers_translucent"))
            return SundialDeferredPatch.capture(source);
        if (name.equals("clrwl_gbuffers_unlit_translucent") || name.equals("clrwl_gbuffers_additive"))
            return SundialDeferredPatch.captureLight(source, false);
        if (name.startsWith("crankshaft_gbuffers_additive") || name.equals("gbuffers_beaconbeam"))
            return SundialDeferredPatch.captureLight(source, true);
        if (name.equals("composite")) return SundialDeferredPatch.composite(0, source);
        if (name.matches("composite[1236]"))
            return SundialDeferredPatch.composite(name.charAt(name.length() - 1) - '0', source);
        return source;
    }

    @Override
    public void validate(DeferredReplayTargets targets) {
        require(targets, 0, GL11.GL_RGBA8);
        require(targets, 1, GL31.GL_RGBA16_SNORM);
        require(targets, 2, GL11.GL_RGBA16);
        for (var index : new int[]{4, 5, 9, 10}) require(targets, index, GL30.GL_RGBA16F);
    }

    @Override
    public DeferredReplayPlan replayPlan(DeferredReplayTargets targets, List<DeferredReplayPass> passes) {
        return SundialReplayResources.plan(targets, passes);
    }

    @Override
    public boolean capturesLights() {
        return true;
    }
}
