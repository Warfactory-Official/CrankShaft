package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;

import java.util.List;
import java.util.stream.Collectors;

final class SundialDeferredPatch {
    private SundialDeferredPatch() { }

    private static String finish(GlslAst ast, String before, String after) {
        return DeferredReplayShaders.compatibilityHeader(ast.source()) + before + ast.body() + after;
    }

    static String opaqueContract(String source) {
        var ast = GlslAst.parse(source);
        for (var call : ast.calls("clrwl_computeFragment").toList()) {
            var value = call.getParameters().getFirst();
            if (GlslAst.matches(value, "albedoData")) value.replaceByAndDelete(ast.expression("mix(vec4(1.0),texAlbedo,vec4(float(useTexAlbedo)))"));
            else if (GlslAst.matches(value, "albedo")) value.replaceByAndDelete(ast.expression("texture(gtexture,texcoord)"));
        }
        return finish(ast, "", "");
    }

    static String capture(String source) {
        var ast = GlslAst.parse(source);
        if (ast.has("_flw_sundialCaptureMain")) return source;
        for (var name : List.of("gbufferData0", "gbufferData1", "gbufferData2"))
            if (!ast.has(name)) throw unsupported("missing output " + name);
        ast.renameFunction("main", "_flw_sundialCaptureMain");
        return DeferredCaptureAllocator.coalesce(finish(ast, "", DeferredReplayShaders.resource("layer_capture.glsl")
                + "void main(){_flw_sundialCaptureMain();if(gl_HelperInvocation)return;flw_captureLayer(gbufferData0,gbufferData1,gbufferData2); }\n"));
    }

    static String captureLight(String source, boolean early) {
        var ast = GlslAst.parse(source);
        if (ast.has("_flw_sundialLightMain")) return source;
        ast.renameFunction("main", "_flw_sundialLightMain");
        var before = new StringBuilder();
        if (!ast.has("screenSize")) before.append("uniform vec2 screenSize;\n");
        if (!ast.has("depthtex1")) before.append("uniform sampler2D depthtex1;\n");
        before.append(DeferredReplayShaders.resource("layer_storage.glsl")).append(DeferredReplayShaders.resource("layer_light.glsl"));
        var value = ast.has("guestUnlit") ? "vec4(guestUnlit.rgb*guestUnlit.a,guestUnlit.a)" : "vec4(guestLight.rgb,0.0)";
        return DeferredCaptureAllocator.coalesce(finish(ast, before.toString(),
                "void main(){_flw_sundialLightMain();if(gl_HelperInvocation)return;ivec2 pixel=ivec2(gl_FragCoord.xy);"
                        + (early ? "" : "if(gl_FragCoord.z<texelFetch(depthtex1,pixel,0).x)")
                        + "flw_captureLight(pixel,gl_FragCoord.z," + value + ",uint(screenSize.x));}\n"));
    }

    static String lightSort(String pixel) { return DeferredSortShader.build(DeferredSortShader.Mode.LIGHT, pixel, ""); }

    static String composite(int index, String source) {
        var ast = GlslAst.parse(source);
        if (ast.has("_flw_sundialCompositeReplay")) return source;
        var before = new StringBuilder();
        if (index == 0) {
            var statements = ast.function("main").getBody().getStatements();
            var first = declaration(statements, "transparentDensity");
            var last = assignment(statements, "texBuffer5");
            if (first < 0 || last <= first) throw unsupported("cloud visibility range");
            var visibility = GlslAst.preprocessed("void main(){" + print(statements.subList(first, last)) + "}");
            var depths = visibility.assignments("waterDepth");
            if (depths.size() != 1) throw unsupported("cloud layer depth input");
            depths.getFirst().value(visibility.expression("uintBitsToFloat(flw_nodes[flw_node].y)"));
            var sort = DeferredSortShader.build(DeferredSortShader.Mode.MATERIAL, "uvec2(gl_FragCoord.xy)",
                    print(visibility.function("main").getBody().getStatements()));
            ast.insertBefore(statements.get(last), sort + lightSort("uvec2(gl_FragCoord.xy)"));
            before.append(DeferredReplayShaders.resource("layer_storage.glsl")).append(DeferredReplayShaders.resource("layer_light.glsl"));
        } else {
            before.append("uniform int flw_oitFarLayer;\n");
            if (index == 1 || index == 3 || index == 6) {
                before.append("uniform bool flw_oitReplaying;layout(r32ui,binding=7)uniform readonly uimage2D flw_layerNode;\n");
                surfaceTests(ast);
            }
            if (index == 1) {
                before.append(DeferredReplayShaders.resource("layer_storage.glsl"))
                        .append(DeferredReplayShaders.resource("layer_light.glsl"))
                        .append("vec3 flw_resolveRefractedLight(vec3 background,vec2 coord,float back,vec4 plane);\n");
                materialLighting(ast);
                guard(ast, "absorption", 1, "texBuffer4");
                ast.append(DeferredReplayShaders.resource("layer_refracted_light.glsl"));
            } else if (index == 2) {
                ast.prependMain("if(flw_oitFarLayer!=0){ivec2 pixel=ivec2(gl_FragCoord.xy);texBuffer4=texelFetch(colortex4,pixel,0);texBuffer5=texelFetch(colortex5,pixel,0);return;}");
            } else if (index == 6) {
                before.append(DeferredReplayShaders.resource("layer_storage.glsl")).append(DeferredReplayShaders.resource("layer_light.glsl"));
                insertAfter(ast, "parallaxOffset", """
                        vec3 _flw_guestLight=texelFetch(colortex10,texel,0).rgb;solidColor+=_flw_guestLight;
                        vec4 _flw_guestUnlit=texelFetch(colortex9,texel,0);solidColor=solidColor*(1.0-_flw_guestUnlit.a)+_flw_guestUnlit.rgb;
                        """);
                before.append("uniform sampler2D colortex10;uniform sampler2D colortex9;\n");
                var statements = ast.function("main").getBody().getStatements();
                var first = declaration(statements, "_flw_guestLight");
                var last = assignment(statements, "texBuffer5");
                if (first < 0 || last <= first || declaration(statements, "_flw_guestUnlit") != first + 2)
                    throw unsupported("unlit opacity merge");
                replaceRange(ast, first + 1, first + 4, "if(!flw_oitActive||flw_overflow!=0u){"
                        + print(statements.subList(first + 1, first + 4))
                        + "}else{solidColor=flw_resolveLight(solidColor,texel,0.0,waterDepth,uvec2(screenSize));}");
                guard(ast, "_flw_guestLight", 0, "texBuffer5");
            }
        }
        return finish(ast, before.toString(), "\nvoid _flw_sundialCompositeReplay(){}\n");
    }

    private static void materialLighting(GlslAst ast) {
        insertAfter(ast, "solidColor", "solidColor.rgb=flw_resolveLight(solidColor.rgb,texel,waterDepth,solidDepth,uvec2(screenSize));");
        insertAfter(ast, "texel", "uint flw_materialNode=flw_oitReplaying?imageLoad(flw_layerNode,texel).r:0u;bool flw_layerUnlit=flw_materialNode!=0u&&(flw_nodes[flw_materialNode].w&FLW_LAYER_UNLIT)!=0u;");
        insertAfter(ast, "waterWorldDir", "vec3 flw_planeNormal=gbufferData.geoNormal*(dot(gbufferData.geoNormal,waterViewPos)<0.0?-1.0:1.0);");
        var radiance = ast.assignments("volumetricLight");
        if (radiance.size() != 1 || ast.expressions("gbufferData.emissive").stream()
                .noneMatch(node -> node == radiance.getFirst().value() || node.hasAncestor(radiance.getFirst().value())))
            throw unsupported("material radiance");
        radiance.getFirst().value(ast.expression("flw_layerUnlit?gbufferData.albedo.rgb*gbufferData.albedo.w:("
                + GlslAst.print(radiance.getFirst().value()) + ")"));
        for (var value : ast.assignments("solidColor.rgb")) {
            var expression = value.value();
            if (expression instanceof MemberAccessExpression member) expression = member.getOperand();
            if (!(expression instanceof FunctionCallExpression call) || call.getFunctionName() == null
                    || !call.getFunctionName().getName().equals("texelFetch") || call.getParameters().isEmpty()
                    || !GlslAst.matches(call.getParameters().getFirst(), "colortex5")) continue;
            value.value(ast.expression("flw_resolveRefractedLight(" + GlslAst.print(value.value())
                    + ",refractionTarget,targetSolidDepth,vec4(flw_planeNormal,-dot(flw_planeNormal,waterViewPos)))"));
        }
        for (var expression : ast.expressions("volumetricLight+=shadow")) {
            var statement = expression.getAncestor(Statement.class);
            statement.replaceByAndDelete(ast.statement("if(!flw_layerUnlit){" + GlslAst.print(statement) + "}"));
        }
    }

    private static void surfaceTests(GlslAst ast) {
        for (var comparison : ast.nodes(LessThanExpression.class).toList()) {
            if (!GlslAst.matches(comparison.getLeft(), "waterDepth")) continue;
            var right = comparison.getRight();
            var depth = GlslAst.matches(right, "solidDepth");
            if (right instanceof MemberAccessExpression member) right = member.getOperand();
            if (right instanceof FunctionCallExpression call && call.getFunctionName() != null
                    && call.getFunctionName().getName().equals("texelFetch") && !call.getParameters().isEmpty()
                    && GlslAst.matches(call.getParameters().getFirst(), "depthtex1")) depth = true;
            if (depth) comparison.replaceByAndDelete(ast.expression("((" + GlslAst.print(comparison)
                    + ")||(flw_oitReplaying&&imageLoad(flw_layerNode,ivec2(gl_FragCoord.xy)).r!=0u))"));
        }
    }

    private static void guard(GlslAst ast, String firstName, int offset, String lastName) {
        var statements = ast.function("main").getBody().getStatements();
        var first = declaration(statements, firstName);
        var last = assignment(statements, lastName);
        if (first < 0 || last <= first + offset) throw unsupported("nearest layer range " + firstName);
        replaceRange(ast, first + offset, last, "if(flw_oitFarLayer==0){" + print(statements.subList(first + offset, last)) + "}");
    }

    private static void replaceRange(GlslAst ast, int first, int end, String replacement) {
        var statements = ast.function("main").getBody().getStatements();
        var removed = List.copyOf(statements.subList(first, end));
        var node = ast.statement(replacement);
        removed.forEach(Statement::detachAndDelete);
        statements.add(first, node);
    }

    private static void insertAfter(GlslAst ast, String name, String source) {
        var statements = ast.function("main").getBody().getStatements();
        var found = declaration(statements, name);
        if (found < 0) throw unsupported("missing declaration " + name);
        ast.insertAfter(statements.get(found), source);
    }

    private static int declaration(List<Statement> statements, String name) {
        for (var index = 0; index < statements.size(); index++)
            if (statements.get(index) instanceof DeclarationStatement statement
                    && statement.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals(name))) return index;
        return -1;
    }

    private static int assignment(List<Statement> statements, String name) {
        for (var index = 0; index < statements.size(); index++)
            if (statements.get(index) instanceof ExpressionStatement statement
                    && statement.getExpression() instanceof AssignmentExpression assignment
                    && GlslAst.matches(assignment.getLeft(), name)) return index;
        return -1;
    }

    private static String print(List<Statement> statements) { return statements.stream().map(GlslAst::print).collect(Collectors.joining("\n")); }

    private static UnsupportedOperationException unsupported(String reason) {
        return new UnsupportedOperationException("Sundial deferred adapter: " + reason);
    }
}
