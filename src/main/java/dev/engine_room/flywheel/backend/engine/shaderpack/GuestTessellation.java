package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GuestShaderTemplates;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.InterpolationQualifier;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.ShaderCompatibility;
import dev.engine_room.flywheel.backend.gl.GlCompat;

import java.util.ArrayList;
import java.util.List;

final class GuestTessellation {
    static final boolean SUPPORTED = GlCompat.CAPABILITIES.OpenGL40 || GlCompat.CAPABILITIES.GL_ARB_tessellation_shader;
    private static final List<Field> FIELDS = fields();
    private static final int ROWS = FIELDS.stream().filter(field -> !field.flat).mapToInt(Field::row).max()
                                          .orElseThrow() + 1;

    private GuestTessellation() {
    }

    static Stages prepare(String vertex, String control, String evaluation, String geometry, boolean contract) {
        if (evaluation == null) {
            if (control != null)
                throw new IllegalStateException("Tessellation control shader has no evaluation shader");
            var stages = GuestGeometry.prepare(vertex, geometry, contract);
            return new Stages(stages.vertex(), null, null, stages.geometry());
        }
        if (!SUPPORTED)
            throw new UnsupportedOperationException("Guest tessellation requires OpenGL tessellation shaders");
        if (control != null) control = ShaderCompatibility.preserveVersion(control);
        evaluation = ShaderCompatibility.preserveVersion(evaluation);
        var bloom = GlslAst.preprocessed(vertex).has("ausm_BloomOnlyMask");
        if (bloom) vertex = rename(vertex, "ausm_BloomOnlyMask", "_flw_tessBloomIn");
        if (contract || bloom) {
            var patchRelay = control != null && controlVertices(control) < 4;
            evaluation = evaluation(evaluation, contract, bloom, control != null, patchRelay);
            if (control != null) control = control(control, contract, bloom, patchRelay);
        }
        var stages = GuestGeometry.prepare(evaluation, geometry, contract);
        return new Stages(vertex, control, stages.vertex(), stages.geometry());
    }

    private static String control(String source, boolean contract, boolean bloom, boolean patchRelay) {
        var result = preamble(source);
        if (contract) {
            result.append("in ").append(block()).append(" _flw_tessVertexIn[];\n");
            if (patchRelay) patch(result, "out");
            else result.append("out ").append(controlBlock()).append(" _flw_tessVertexOut[];\n");
        }
        if (bloom) result.append("in float _flw_tessBloomIn[];\npatch out float _flw_tessBloom;\n");
        var relay = new StringBuilder("\n");
        if (contract && !patchRelay) {
            for (var field : FIELDS)
                relay.append("_flw_tessVertexOut[gl_InvocationID].").append(field.name)
                     .append("=_flw_tessVertexIn[min(gl_InvocationID,3)].").append(field.name).append(";\n");
        }
        relay.append("if(gl_InvocationID==0) {\n");
        if (contract && patchRelay) {
            // Material coordinates belong to the input patch, independently of the pack's output control-point count.
            relay.append("for(int i=0;i<4;i++) {\n");
            for (var field : FIELDS)
                if (!field.flat) relay.append(packed(field, "i")).append("=_flw_tessVertexIn[i].")
                                      .append(field.name).append(";\n");
            relay.append("}\n");
            for (var field : FIELDS)
                if (field.flat) relay.append("_flw_tessPatch.").append(field.name)
                                     .append("=_flw_tessVertexIn[0].").append(field.name).append(";\n");
        }
        if (bloom) relay.append("_flw_tessBloom=_flw_tessBloomIn[0];\n");
        relay.append("}\n");
        // TCS barriers must remain directly inside main, so do not wrap the pack entry point in a function.
        return result.append(prependMain(GuestShaderTemplates.stripPackDirectives(source), relay.toString())).toString();
    }

    private static String evaluation(String source, boolean contract, boolean bloom, boolean control,
                                     boolean patchRelay) {
        var result = preamble(source);
        if (contract) {
            if (patchRelay) patch(result, "in");
            else result.append("in ").append(control ? controlBlock() : block()).append(" _flw_tessVertexIn[];\n");
            result.append("out ").append(GuestContractShaders.VARYINGS);
        }
        if (bloom) result.append(control ? "patch in float _flw_tessBloom;\n" : "in float _flw_tessBloomIn[];\n")
                         .append("out float ausm_BloomOnlyMask;\n");
        if (contract) {
            // Authored TES code can select its own basis, just as geometry code selects a source vertex.
            result.append("void clrwl_setVertexOut(vec4 w) {\n");
            for (var field : FIELDS) {
                result.append(field.name).append('=');
                if (field.flat) result.append(value(field, 0, patchRelay));
                else for (var index = 0; index < 4; index++) {
                    if (index != 0) result.append('+');
                    result.append(value(field, index, patchRelay)).append("*w[").append(index).append(']');
                }
                result.append(";\n");
            }
            result.append("}\n");
        }
        result.append(rename(GuestShaderTemplates.stripPackDirectives(source), "main", "_flw_packTessMain"));
        result.append("\nvoid main() {\n");
        if (contract) {
            var layout = GlslAst.preprocessed(source).nodes(NamedLayoutQualifierPart.class)
                    .map(value -> value.getName().getName()).toList();
            var triangles = layout.contains("triangles");
            var isolines = layout.contains("isolines");
            result.append(triangles ? "clrwl_setVertexOut(vec4(gl_TessCoord.xyz,0.0));\n"
                    : isolines ? "clrwl_setVertexOut(vec4(1.0-gl_TessCoord.x,gl_TessCoord.x,0.0,0.0));\n" : """
                    vec2 t=gl_TessCoord.xy;
                    clrwl_setVertexOut(vec4((1.0-t.x)*(1.0-t.y),t.x*(1.0-t.y),t.x*t.y,(1.0-t.x)*t.y));
                    """);
        }
        if (bloom) result.append(
                control ? "ausm_BloomOnlyMask=_flw_tessBloom;\n" : "ausm_BloomOnlyMask=_flw_tessBloomIn[0];\n");
        return result.append("_flw_packTessMain();\n}\n").toString();
    }

    private static String value(Field field, int index, boolean control) {
        return control ? field.flat ? "_flw_tessPatch." + field.name : packed(field, Integer.toString(index))
                : "_flw_tessVertexIn[" + index + "]." + field.name;
    }

    private static String packed(Field field, String index) {
        var width = Integer.parseInt(field.type.substring(field.type.length() - 1));
        return "_flw_tessPatch.values[(" + index + ")*" + ROWS + "+" + field.row + "]."
                + "xyzw".substring(field.component, field.component + width);
    }

    private static String prependMain(String source, String body) {
        var ast = GlslAst.preprocessed(source);
        ast.prependMain(body);
        return ast.body();
    }

    private static String block() { return GuestContractShaders.varyingBlock("ClrwlVertexData"); }

    private static String controlBlock() { return GuestContractShaders.varyingBlock("ClrwlTessVertexData"); }

    private static int controlVertices(String source) {
        var values = GlslAst.preprocessed(source).nodes(NamedLayoutQualifierPart.class)
                .filter(part -> part.getName().getName().equals("vertices")).toList();
        if (values.size() != 1 || !(values.getFirst().getExpression() instanceof LiteralExpression value)
                || !value.isInteger() || value.getInteger() <= 0)
            throw new UnsupportedOperationException("Tessellation control shader needs one literal vertex count");
        return Math.toIntExact(value.getInteger());
    }

    private static void patch(StringBuilder result, String storage) {
        result.append("patch ").append(storage).append(" ClrwlPatchData {\n");
        // GLSL arrays reserve a full vector per element even for vec2/vec3; keep the patch below the minimum storage limit.
        result.append("vec4 values[").append(4 * ROWS).append("];\n");
        for (var field : FIELDS) if (field.flat) result.append(field.type).append(' ').append(field.name).append(";\n");
        result.append("} _flw_tessPatch;\n");
    }

    private static StringBuilder preamble(String source) {
        var result = new StringBuilder(GuestShaderTemplates.HEADER);
        GuestShaderTemplates.copyExtensions(source, result);
        return result;
    }

    private static String rename(String source, String name, String replacement) {
        var ast = GlslAst.preprocessed(source);
        if (name.equals("main")) ast.renameFunction(name, replacement);
        else ast.rename(name, replacement);
        return ast.source();
    }

    private static List<Field> fields() {
        var ast = GlslAst.preprocessed("out " + GuestContractShaders.VARYINGS);
        var block = ast.nodes(InterfaceBlockDeclaration.class).findFirst().orElseThrow();
        var result = new ArrayList<Field>();
        int row = 0, component = 0;
        for (var member : block.getStructBody().getMembers()) {
            var type = GlslAst.print(member.getType().getTypeSpecifier()).strip();
            var flat = member.getType().getTypeQualifier() != null && member.getType().getTypeQualifier().getParts().stream()
                    .anyMatch(part -> part instanceof InterpolationQualifier qualifier && qualifier.interpolationType == InterpolationQualifier.InterpolationType.FLAT);
            for (var declarator : member.getDeclarators()) {
                var name = declarator.getName().getName();
                if (flat) { result.add(new Field(type, name, true, -1, -1)); continue; }
                var width = Integer.parseInt(type.substring(type.length() - 1));
                if (component + width > 4) { row++; component = 0; }
                result.add(new Field(type, name, false, row, component));
                component += width;
                if (component == 4) { row++; component = 0; }
            }
        }
        return List.copyOf(result);
    }

    record Stages(String vertex, String control, String evaluation, String geometry) {
    }

    private record Field(String type, String name, boolean flat, int row, int component) {
    }
}
