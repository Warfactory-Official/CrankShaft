package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.google.gson.JsonObject;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class SeusDeferredAdapter implements DeferredOitAdapter {
    static final SeusDeferredAdapter STANDARD = new SeusDeferredAdapter(false);
    static final SeusDeferredAdapter MOTION = new SeusDeferredAdapter(true);
    private static final Pattern TEXTURED_TARGETS = Pattern.compile("DRAWBUFFERS\\s*:\\s*12\\b");
    private final boolean motion;

    private SeusDeferredAdapter(boolean motion) {
        this.motion = motion;
    }

    private static void require(DeferredReplayTargets targets, int index, int format) {
        if (targets.get(index).format() != format)
            throw new UnsupportedOperationException("SEUS colortex" + index + " format changed");
    }

    @Override
    public ContractPatches.Plan stage(GuestShaderPack pack, Map<String, String> originals,
                                      JsonObject recipe) throws IOException {
        var changes = new LinkedHashMap<String, String>();
        for (var path : originals.keySet()) {
            if (!path.equals("gbuffers_water.fsh") && !path.endsWith("/gbuffers_water.fsh")) continue;
            var directory = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            for (var extension : List.of(".vsh", ".fsh", ".gsh", ".tcs", ".tes")) {
                var nativeSource = originals.get(directory + "gbuffers_water" + extension);
                if (nativeSource != null) changes.put(directory + "clrwl_gbuffers_translucent" + extension,
                        nativeSource + "\nvoid _flw_nativeTranslucent() {}\n");
            }
        }
        changes.put("shaders.properties", originals.getOrDefault("shaders.properties", "") + """
                
                iris.features.optional = SSBO
                uniform.vec2.screenSize = vec2(viewWidth, viewHeight)
                bufferObject.0 = 4 true 1.0 1.0
                bufferObject.1 = 2097152
                bufferObject.2 = 16
                """);
        changes.put("colorwheel.properties", originals.getOrDefault("colorwheel.properties", "") + '\n'
                + recipe.get("properties").getAsString());
        originals.putAll(changes);
        return new ContractPatches.Plan(Map.copyOf(originals), pack.prefix(), false,
                (motion ? DeferredOitProfile.SEUS_HRR3 : DeferredOitProfile.SEUS).name());
    }

    @Override
    public String transform(String name, String source, int type) {
        if (type == GL20.GL_VERTEX_SHADER && motion && name.equals("gbuffers_entities")) {
            var ast = GlslAst.parse(source);
            if (ast.has("_flw_zeroEntityVelocity")) return source;
            var inputs = ast.globals().filter(declaration -> (GlslAst.storage(declaration, StorageType.IN)
                    || GlslAst.storage(declaration, StorageType.ATTRIBUTE)) && declaration.getMembers().stream()
                    .anyMatch(member -> member.getName().getName().equals("at_velocity"))).toList();
            if (inputs.size() != 1 || inputs.getFirst().getMembers().size() != 1)
                throw new UnsupportedOperationException("SEUS entity velocity declaration changed");
            // Legacy entity vertices have no velocity attribute; keep it from aliasing another input.
            inputs.getFirst().getParent().replaceByAndDelete(ast.declaration("const vec3 at_velocity=vec3(0.0);"));
            ast.append("void _flw_zeroEntityVelocity(){}");
            return ast.source();
        }
        if (type != GL20.GL_FRAGMENT_SHADER) return source;
        if (name.equals("gbuffers_water") || name.equals("clrwl_gbuffers_translucent"))
            return SeusDeferredPatch.capture(source, motion, true);
        if (name.equals("gbuffers_textured") && TEXTURED_TARGETS.matcher(source).find())
            return SeusDeferredPatch.capture(source, false, false);
        if (name.equals("composite")) return SeusDeferredPatch.material(source, 0);
        if (name.matches("composite[1-4]"))
            return SeusDeferredPatch.material(source, name.charAt(name.length() - 1) - '0');
        return source;
    }

    @Override
    public void validate(DeferredReplayTargets targets) {
        require(targets, 0, GL11.GL_RGBA8);
        require(targets, 1, GL11.GL_RGBA16);
        require(targets, 2, GL11.GL_RGBA16);
        require(targets, 7, GL30.GL_RGBA16F);
        if (motion) require(targets, 8, GL30.GL_RGB16F);
    }

    @Override
    public DeferredReplayPlan replayPlan(DeferredReplayTargets targets, List<DeferredReplayPass> passes) {
        return SeusReplayResources.plan(targets, passes, motion);
    }
}
