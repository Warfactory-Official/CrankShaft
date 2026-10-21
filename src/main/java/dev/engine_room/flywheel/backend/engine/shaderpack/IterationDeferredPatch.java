package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.util.Type;

import java.util.List;

final class IterationDeferredPatch {
    private IterationDeferredPatch() { }

    private static String finish(GlslAst ast, String declarations, String additions) {
        return DeferredReplayShaders.compatibilityHeader(ast.source()) + declarations + '\n' + ast.body() + '\n' + additions;
    }

    static String shadow(String source) {
        var ast = GlslAst.parse(source);
        if (!ast.has("g_color") && !ast.has("v_color")) return source;
        ast.globals().filter(declaration -> GlslAst.storage(declaration, StorageType.IN)
                || GlslAst.storage(declaration, StorageType.OUT)).forEach(declaration -> {
            if (declaration.getMembers().stream().anyMatch(member -> List.of("g_color", "v_color").contains(member.getName().getName()))
                    && declaration.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric
                    && numeric.type == Type.F32VEC3) numeric.type = Type.F32VEC4;
        });
        ast.replaceExpression("g_color=gl_Color.rgb", "g_color=gl_Color", false);
        ast.replaceExpression("vec4(g_color[0],blockLight.x)", "vec4(g_color[0].rgb,blockLight.x)", false);
        ast.replaceExpression("vec4((g_color[0],blockLight.x))", "vec4(g_color[0].rgb,blockLight.x)", false);
        ast.replaceExpression("albedoTex.rgb*=v_color", "albedoTex.rgb*=v_color.rgb", false);
        for (var condition : ast.expressions("albedoTex.a<0.004"))
            ast.insertBefore(condition.getAncestor(Statement.class), "albedoTex.a*=v_color.a;");
        return ast.source();
    }

    static String contract(String source, boolean water) {
        var ast = GlslAst.parse(source);
        ast.replaceExpression("albedo.rgb*=fragColor.rgb", "albedo.rgb=mix(albedo.rgb,fragColor.rgb,fragColor.a)", true);
        // 1.12.2: material light inputs have no modern half-texel offset.
        for (var value : List.of("blockLight.x", "blockLight.y", "blockLight"))
            ast.replaceExpression(value + "*1.0666667-0.03333334", value + "*1.0666667", false);
        if (!water) return ast.source();
        var mappings = ast.assignments("blockLight").stream().filter(assignment -> {
            if (!(assignment.value() instanceof FunctionCallExpression call) || call.getParameters().size() != 2
                    || !GlslAst.matches(call.getParameters().getFirst(), "0.0")) return false;
            return ast.expressions("blockLight.y").stream().anyMatch(node -> node.hasAncestor(call));
        }).toList();
        if (mappings.size() != 1) throw new UnsupportedOperationException("Iteration translucent light mapping changed");
        mappings.getFirst().value(ast.expression("clamp(blockLight*(16.0/15.0),0.0,1.0)"));
        ast.replaceExpression("!isWater&&albedoAlpha==1.0", "!isWater&&(!_flw_borrowedBlock()||albedoAlpha==1.0)", true);
        var material = ast.expressions("materialEnc.y/=255.0");
        if (material.isEmpty()) throw new UnsupportedOperationException("Iteration material encoding changed");
        for (var expression : material) ast.insertAfter(expression.getAncestor(Statement.class), "if(_flw_isUnlit())blockLight.x=1.0;");
        var albedo = ast.expressions("albedoEnc=vec2(Pack2xU8_to_U16(specularTex.rg),Pack2xU8_to_U16(specularTex.ba))");
        if (albedo.isEmpty()) throw new UnsupportedOperationException("Iteration albedo encoding changed");
        for (var expression : albedo) ast.insertBefore(expression.getAncestor(Statement.class), "if(_flw_isUnlit())specularTex.a=0.2;");
        for (var name : List.of("T", "B")) for (var assignment : ast.assignments(name)) {
            if (!(assignment.value() instanceof FunctionCallExpression call) || call.getFunctionName() == null
                    || !call.getFunctionName().getName().equals("normalize") || call.getParameters().size() != 1) continue;
            assignment.value(call.getParameters().getFirst().cloneInto(ast.tree.getRoot()));
            if (name.equals("B")) ast.insertAfter(assignment.statement(), """
                    if(min(dot(T,T),dot(B,B))==0.0){T=cross(abs(N.y)<0.9?vec3(0,1,0):vec3(1,0,0),N);B=cross(N,T);}
                    T=normalize(T);B=normalize(B);
                    """);
        }
        if (!ast.has("framebuffer_gwater")) {
            ast.appendMain("framebuffer_gwater=vec4(normalEnc,Pack2xU8_to_U16(vec2(dist*0.02,blockLight.y+1e-6)),float(isWater));");
            return finish(ast, "layout(location=3)out vec4 framebuffer_gwater;", "/* RENDERTARGETS: 0,3,4,5 */");
        }
        return ast.source();
    }

    static String captureMaterial(String source) {
        var ast = GlslAst.parse(source);
        var scopes = ast.assignments("parallaxWaterDist").stream().filter(GlslAst.Assignment::declared)
                .map(value -> value.node().getAncestor(CompoundStatement.class)).distinct().toList();
        if (scopes.isEmpty()) throw new UnsupportedOperationException("Iteration water-depth capture changed");
        for (var scope : scopes) scope.getStatements().add(ast.statement("_flw_waterDepth=vec2(parallaxWaterDist,waterDist);"));
        var water = ast.has("framebuffer_gwater");
        ast.renameFunction("main", "_flw_materialMain");
        var result = finish(ast, "vec2 _flw_waterDepth=vec2(0.0);\n" + DeferredReplayShaders.resource("iteration_layers.glsl")
                + DeferredReplayShaders.resource("iteration_materials.glsl"), """
                void main(){_flw_materialMain();if(gl_HelperInvocation)return;
                    ivec2 pixel=ivec2(gl_FragCoord.xy);
                    if(gl_FragCoord.z<texelFetch(depthtex1,pixel,0).x)
                        flw_captureMaterial(pixel,gl_FragCoord.z,framebuffer_albedo,framebuffer_gtransData,
                            framebuffer_gtransNormal,%s,_flw_waterDepth,uint(screenSize.x));}
                """.formatted(water ? "framebuffer_gwater" : "vec4(0.0)"));
        return DeferredCaptureAllocator.coalesce(result);
    }

    static String captureLight(String source, boolean additive, boolean early) {
        var ast = GlslAst.parse(source);
        var declarations = new StringBuilder();
        for (var uniform : List.of("pixelData2D", "depthtex1"))
            if (!ast.has(uniform)) declarations.append("uniform sampler2D ").append(uniform).append(";\n");
        if (!ast.has("screenSize")) declarations.append("uniform vec2 screenSize;\n");
        declarations.append(DeferredReplayShaders.resource("iteration_layers.glsl"));
        ast.renameFunction("main", "_flw_lightMain");
        var radiance = additive ? "vec4(guestEmission.rgb+guestDisplay.rgb*texelFetch(pixelData2D,ivec2(0),0).x*0.13,0.0)"
                : "vec4(guestUnlit.rgb*guestUnlit.a,guestUnlit.a)";
        return DeferredCaptureAllocator.coalesce(finish(ast, declarations.toString(),
                "void main(){_flw_lightMain();if(gl_HelperInvocation)return;ivec2 pixel=ivec2(gl_FragCoord.xy);"
                        + (early ? "" : "if(gl_FragCoord.z<texelFetch(depthtex1,pixel,0).x)")
                        + "flw_captureLight(pixel,gl_FragCoord.z," + radiance + ",uint(screenSize.x));}\n"));
    }

    static String resolve(String source, boolean refraction) {
        var ast = GlslAst.parse(source);
        if (ast.has("fsrPixelSize") || ast.has("fsrRenderScale") || ast.has("lodProjection0"))
            throw new UnsupportedOperationException("Iteration replay requires native resolution and depth");
        var declarations = new StringBuilder(DeferredReplayShaders.resource("iteration_layers.glsl"));
        String additions = "";
        Statement anchor;
        String body;
        if (refraction) {
            declarations.append("uniform bool flw_oitReplaying;vec3 flw_resolveRefractedLight(vec3 background,vec2 coord,float back,vec4 plane);\n");
            additions = DeferredReplayShaders.resource("iteration_refracted_light.glsl");
            anchor = ast.function("GlassRefraction").getBody().getStatements().getLast();
            body = """
                    if(!flw_oitReplaying||GetTransMaterialID(texelCoord)<100.0||GetTransMaterialID(texelCoord)>104.0){
                        float back=texelFetch(depthtex1,ivec2(refractCoord*screenSize),0).x;
                        if(mask.water+mask.stainedGlass>0.5){
                            vec3 planeNormal=mask.water>0.5?vertexNormal:vertexNormal*mat3(gbufferModelViewInverse);
                            planeNormal*=dot(planeNormal,viewDir)<0.0?-1.0:1.0;
                            color=flw_resolveRefractedLight(color,refractCoord,back,vec4(planeNormal,-dot(planeNormal,viewPos)));
                        }else color=flw_resolveLight(color,ivec2(refractCoord*screenSize),depth,back,uvec2(screenSize));
                    }
                    """;
        } else {
            declarations.append("uniform sampler2D colortex13,colortex14,colortex18;\n");
            var outputs = ast.assignments("framebuffer_mainOutput");
            if (outputs.size() != 1) throw new UnsupportedOperationException("Iteration light merge changed");
            anchor = outputs.getFirst().statement();
            body = """
                    if(!flw_oitActive||flw_overflow!=0u){
                        vec4 unlit=texelFetch(colortex18,texelCoord,0);color=color*(1.0-unlit.a)+unlit.rgb;
                        color+=texelFetch(colortex13,texelCoord,0).rgb+texelFetch(colortex14,texelCoord,0).rgb*(texelFetch(pixelData2D,ivec2(0),0).x*0.13);
                    }else color=flw_resolveLight(color,texelCoord,0.0,texelFetch(depthtex0,texelCoord,0).x,uvec2(screenSize));
                    """;
        }
        ast.insertBefore(anchor, body);
        return finish(ast, declarations.toString(), additions);
    }

    static String guard(String source, boolean diffuse, boolean compute) {
        var ast = GlslAst.parse(source);
        var declarations = new StringBuilder();
        if (!ast.has("flw_count")) declarations.append(DeferredReplayShaders.resource("iteration_layers.glsl"));
        var materials = ast.has("flw_materialAt") ? "" : DeferredReplayShaders.resource("iteration_materials.glsl");
        if (!ast.has("screenSize")) declarations.append("uniform vec2 screenSize;\n");
        if (!ast.has("flw_oitReplaying")) declarations.append("uniform bool flw_oitReplaying;\n");
        declarations.append("uniform int flw_oitFarLayer,flw_oitLayerIndex;\n");
        var skip = "flw_oitReplaying&&flw_oitActive&&flw_overflow==0u&&flw_maxLayers>1u&&uint(flw_oitLayerIndex)!=flw_maxLayers-1u";
        var body = new StringBuilder();
        if (!compute) {
            declarations.append("layout(r32ui,binding=7)uniform readonly uimage2D flw_layerNode;\n");
            body.append("if(").append(skip).append("&&imageLoad(flw_layerNode,ivec2(gl_FragCoord.xy)).r==0u)discard;\n");
        }
        if (diffuse) body.append("if(").append(skip).append("&&(flw_tiles[flw_oitLayerIndex]&1u)==0u)").append(compute ? "return;\n" : "discard;\n");
        ast.prependMain(body.toString());
        return finish(ast, declarations.toString(), materials);
    }
}
