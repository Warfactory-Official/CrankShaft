package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.loop.ForLoopStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;

import java.util.ArrayList;
import java.util.Set;

final class SeusDeferredPatch {
    private SeusDeferredPatch() { }

    static String capture(String source, boolean motion, boolean opaqueReady) {
        var ast = GlslAst.parse(source);
        // AUSM runs interface transforms during preprocessing and again immediately before compilation.
        if (ast.has("_flw_seusCaptureMain")) return source;
        if (!ast.has("OutputGBufferDataTransparent"))
            throw new UnsupportedOperationException("SEUS transparent encoder is missing");
        ast.function("main");
        ast.renameFunction("main", "_flw_seusCaptureMain");
        var result = new StringBuilder(DeferredReplayShaders.compatibilityHeader(source));
        if (!ast.has("screenSize")) result.append("uniform vec2 screenSize;\n");
        if (!ast.has("depthtex1")) result.append("uniform sampler2D depthtex1;\n");
        result.append(DeferredReplayShaders.resource("layer_storage.glsl")).append(ast.body());
        result.append(DeferredReplayShaders.resource("seus_capture.glsl")
                .replace("_FLW_SEUS_OPAQUE_READY", Boolean.toString(opaqueReady)));
        result.append("void main(){_flw_seusCaptureMain();if(gl_HelperInvocation)return;flw_captureSeus(gl_FragData[0],gl_FragData[1],")
                .append(motion ? "gl_FragData[2].xyz" : "vec3(0.0)").append(");}\n");
        return DeferredCaptureAllocator.coalesce(result.toString());
    }

    static String material(String source, int stage) {
        var ast = GlslAst.parse(source);
        if (ast.has("_flw_seusMaterialReplay")) return source;
        var main = ast.function("main");
        if (stage == 4) {
            var targets = new ArrayList<Statement>();
            var fog = Set.of("LandAtmosphericScattering", "UnderwaterFog", "UnderLavaFog");
            ast.calls("WorldPosToShadowProjPos").filter(call -> call.hasAncestor(main)).forEach(call -> {
                var loop = call.getAncestor(ForLoopStatement.class);
                if (loop != null) targets.add(loop);
            });
            for (var name : fog) ast.calls(name).filter(call -> call.hasAncestor(main)).forEach(call -> {
                var statement = call.getAncestor(ExpressionStatement.class);
                if (statement != null) targets.add(statement);
            });
            var guards = targets.stream().distinct().filter(statement -> targets.stream()
                    .noneMatch(other -> other != statement && statement.hasAncestor(other))).toList();
            guards.forEach(statement -> statement.replaceByAndDelete(ast.statement(
                    "{if(flw_oitFarLayer==0){" + GlslAst.print(statement) + "}}")));
        }
        ast.prependMain("if(flw_oitReplaying&&flw_oitFarLayer!=0&&imageLoad(flw_layerNode,ivec2(gl_FragCoord.xy)).r==0u)discard;");
        return DeferredReplayShaders.compatibilityHeader(source)
                + "uniform int flw_oitFarLayer;uniform bool flw_oitReplaying;layout(r32ui,binding=7)uniform readonly uimage2D flw_layerNode;\n"
                + ast.body() + "\nvoid _flw_seusMaterialReplay(){}\n";
    }
}
