package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GuestShaderTemplates;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.ShaderCompatibility;

final class GuestGeometry {
    private GuestGeometry() { }

    static Stages prepare(String vertex, String geometry, boolean contract) {
        if (geometry == null) return new Stages(vertex, null);
        geometry = ShaderCompatibility.preserveVersion(geometry);
        if (contract) geometry = GuestContractShaders.geometry(geometry);
        var vertexAst = GlslAst.preprocessed(vertex);
        if (!vertexAst.has("ausm_BloomOnlyMask")) return new Stages(vertex, geometry);
        vertexAst.rename("ausm_BloomOnlyMask", "_flw_ausmBloomMaskIn");
        // AUSM injects this per-block flag into vertex/fragment stages but does not bridge geometry stages.
        var result = new StringBuilder(GuestShaderTemplates.HEADER);
        GuestShaderTemplates.copyExtensions(geometry, result);
        result.append("""
                in float _flw_ausmBloomMaskIn[];
                out float ausm_BloomOnlyMask;
                void _flw_emitVertex() {
                    ausm_BloomOnlyMask=_flw_ausmBloomMaskIn[0];
                    EmitVertex();
                }
                """);
        var ast = GlslAst.preprocessed(geometry);
        ast.renameFunction("EmitVertex", "_flw_emitVertex");
        return new Stages(vertexAst.source(), result.append(ast.body()).toString());
    }

    record Stages(String vertex, String geometry) { }
}
