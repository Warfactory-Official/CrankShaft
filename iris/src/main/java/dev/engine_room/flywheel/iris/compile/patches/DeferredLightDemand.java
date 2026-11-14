package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.LightDemandSources;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.IrisRuntime;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;

import java.util.Map;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

/**
 * Reuses the resolve's material paths while replacing light lookups with per-pixel demand writes.
 */
final class DeferredLightDemand {
    private DeferredLightDemand() {
    }

    static String enable(String source) {
        String enabled = source.replaceAll("\\bconst\\s+bool\\s+flw_demandActive\\s*=\\s*false\\s*;",
                "const bool flw_demandActive = true;");
        return simpleInterlock() ? enabled.replaceAll("\\bconst\\s+bool\\s+flw_lightSimpleEnabled\\s*=\\s*false\\s*;",
                "const bool flw_lightSimpleEnabled = true;") : enabled;
    }

    static boolean simpleInterlock() {
        return !IrisRuntime.EXPERIMENTAL && !VkContext.isVulkanHost() && GlCompat.SUPPORTS_FRAGMENT_INTERLOCK;
    }

    static void interlock(TranslationUnit tree, Root root, ASTParser parser) {
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "#extension GL_ARB_fragment_shader_interlock : require\n", "layout(pixel_interlock_unordered) in;");
        ShaderDeclarations.uniform(tree, root, parser, "sampler2D", "depthtex0");
    }

    static String captureMain(boolean additive, boolean early, String value, boolean interlock) {
        String prefix = "void main() { _flw_lightMain(); ivec2 pixel = ivec2(gl_FragCoord.xy);";
        if (interlock) prefix += " bool flw_simple = false; beginInvocationInterlockARB();"
                + " if (flw_oitActive && !gl_HelperInvocation && flw_lightPhase == 2) {"
                + " vec4 flw_simpleValue = " + value + ";"
                + " if (all(equal(flw_simpleValue, vec4(0.0)))) flw_simple = true; else {"
                + " uint flw_simpleMode = flw_lightSimpleMode(pixel, uvec2(screenSize));"
                + " if (flw_lightSimpleCandidate(flw_simpleMode)) {"
                + " float flw_simpleCut = (flw_simpleMode & FLW_LIGHT_SIMPLE_INITIALIZED) != 0u"
                + " ? uintBitsToFloat(flw_simpleMode & 0x3fffffffu) : texelFetch(depthtex0, pixel, 0).x;"
                + " flw_captureSimpleLight(pixel, gl_FragCoord.z, flw_simpleValue, uvec2(screenSize), flw_simpleMode,"
                + " flw_simpleCut, texelFetch(depthtex1, pixel, 0).x);"
                + " flw_simple = true; } } }"
                + " endInvocationInterlockARB(); if (flw_simple) return;";
        return prefix + " if (gl_HelperInvocation) return;"
                + (early ? "" : " if (" + (additive ? "flw_lightPhase == 2 || " : "") + "!flw_captureTest"
                + " || (_FLW_REVERSED_DEPTH"
                + " ? gl_FragCoord.z > flw_captureLimit(pixel, texelFetch(depthtex1, pixel, 0).x)"
                + " : gl_FragCoord.z < flw_captureLimit(pixel, texelFetch(depthtex1, pixel, 0).x)))")
                + " flw_captureLight(pixel, gl_FragCoord.z, " + value + ", uvec2(screenSize), "
                + (additive ? "flw_lightPhase == 2" : "false") + "); }";
    }

    static String preparedMaterialSort(String source) {
        return source.replace("flw_run < flw_length;", "flw_run < flw_length && !flw_demandActive;");
    }

    static LightDemandSources build(boolean iteration, ProgramSet programs, Map<String, String> fragments) {
        var source = programs.getComposite(ProgramArrayId.Composite)[iteration ? 50 : 1];
        String resolve = fragments.get(source.getName());
        String query = transform(resolve, (tree, root, parser) -> {
            var main = function(root, "main");
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "float flw_demandCut(ivec2 pixel);", "float flw_demandOpaque(ivec2 pixel);");
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.END,
                    "float flw_demandCut(ivec2 pixel) { return texelFetch(depthtex0, pixel, 0).x; }",
                    "float flw_demandOpaque(ivec2 pixel) { return texelFetch(depthtex1, pixel, 0).x; }");
            var far = function(root, "_flw_farLayer");
            var original = calls(far, root, "flw_resolveLight").getFirst();
            String front = ASTPrinter.printSimple(original.getParameters().get(2));
            String back = ASTPrinter.printSimple(original.getParameters().get(3));
            if (!iteration) {
                front = "uintBitsToFloat(flw_nodes[flw_farNode].y)";
                back = "flw_farBack";
            }
            far.getBody().replaceByAndDelete(parser.parseStatement(root,
                    "{ flw_resolveLight(flw_farBackground, texel, " + front + ", " + back
                            + ", uvec2(screenSize)); return flw_farBackground; }"));
            function(root, "flw_resolveLight").getBody().replaceByAndDelete(parser.parseStatement(root,
                    "{ if ((_FLW_REVERSED_DEPTH ? front > back : front < back) && all(lessThan(uvec2(pixel), size))) {"
                            + " float cut = flw_demandCut(pixel), opaque = flw_demandOpaque(pixel);"
                            + " float flw_nearPlane = _FLW_REVERSED_DEPTH ? 1.0 : 0.0;"
                            + " bool aligned = (front == flw_nearPlane || front == cut || front == opaque)"
                            + " && (back == flw_nearPlane || back == cut || back == opaque);"
                            + mark(iteration, "pixel", "size", "aligned ? 1u : 5u") + " } return background; }"));
            function(root, "flw_resolveRefractedLight").getBody().replaceByAndDelete(parser.parseStatement(root,
                    "{ ivec2 pixel = ivec2(coord * screenSize);"
                            + " if ((_FLW_REVERSED_DEPTH ? back < 1.0 : back > 0.0)"
                            + " && all(lessThan(uvec2(pixel), uvec2(screenSize)))) {"
                            + mark(iteration, "pixel", "uvec2(screenSize)", "5u") + " } return background; }"));
            Statement last;
            if (iteration) {
                last = calls(main, root, "GlassRefraction").getFirst().getAncestor(Statement.class);
            } else {
                var lookup = calls(main, root, "flw_resolveRefractedLight").getFirst();
                last = lookup.getAncestor(ExpressionStatement.class);
            }
            while (last.getParent() != main.getBody()) last = last.getParent().getAncestor(Statement.class);
            truncate(main, last, false);
            String nativeFront = iteration ? "depth != opaqueDepth"
                    : "(" + DeferredOitProfile.REVERSED_DEPTH + " ? waterDepth > solidDepth : waterDepth < solidDepth)";
            String nativePixel = iteration ? "texelCoord" : "texel";
            var statements = main.getBody().getStatements();
            statements.add(statements.indexOf(last), parser.parseStatement(root,
                    "if (" + nativeFront + ") {" + mark(iteration, nativePixel, "uvec2(screenSize)", "3u") + "}"));
            for (var call : calls(main, root, "imageStore"))
                call.getAncestor(ExpressionStatement.class).replaceByAndDelete(parser.parseStatement(root, ";"));
            RearReflectionCompute.fragmentPosition(tree, root, parser);
            RearReflectionCompute.vertexInputs(tree, root, parser, source.getVertexSource().orElseThrow());
            String globals = RearReflectionCompute.pixelGlobals(tree);
            root.rename("main", "_flw_demandMain");
            RearReflectionCompute.computeStage(tree, root, parser);
            String coordinate = iteration ? "texCoord" : "texcoord";
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);"
                            + " if (!flw_oitActive || flw_overflow != 0u"
                            + " || any(greaterThanEqual(pixel, ivec2(screenSize)))) return;"
                            + " _flw_fragCoord = vec4(vec2(pixel) + 0.5, 0.5, 1.0); "
                            + coordinate + " = (vec2(pixel) + 0.5) / screenSize; " + globals
                            + " _flw_vertexInputs(); " + coordinate + " = (vec2(pixel) + 0.5) / screenSize;"
                            + " _flw_demandMain(); }");
        });
        return new LightDemandSources(enable(prepare(iteration)), enable(query));
    }

    private static String mark(boolean iteration, String pixel, String size, String bits) {
        String heads = iteration ? "flw_heads" : "flw_lightHeads";
        return "atomicOr(" + heads + "[flw_lightDemandOffset(uint((" + pixel + ").y) * (" + size
                + ").x + uint((" + pixel + ").x), " + size + ")], " + bits + ");";
    }

    private static String prepare(boolean iteration) {
        String materialHeads = iteration ? "flw_materialHeads" : "flw_heads";
        String lightHeads = iteration ? "flw_heads" : "flw_lightHeads";
        String storage = iteration ? DeferredOitProfile.resource("iteration_layers.glsl")
                + DeferredOitProfile.resource("iteration_materials.glsl")
                : DeferredOitProfile.resource("layer_storage.glsl") + DeferredOitProfile.resource("layer_light.glsl");
        String sort = DeferredOitProfile.resource("layer_sort.glsl")
                                        .replace(
                                                "flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);",
                                                "")
                                        .replace("_FLW_VISIBILITY_BODY", "")
                                        .replace("_FLW_SORT_PIXEL", "gl_GlobalInvocationID.xy")
                                        .replace("_FLW_SORT_COUNTS_LAYERS", "false")
                                        .replace("_FLW_SORT_LAYER_BIAS", "0u")
                                        .replace("flw_layerBefore(flw_left, flw_right)",
                                                iteration ? "flw_materialBefore(flw_right, flw_left)" : "flw_layerBefore(flw_right, flw_left)")
                                        .replace("flw_heads", materialHeads).replace(" * 2u", "");
        return "#version 430 compatibility\nlayout(local_size_x = 8, local_size_y = 8) in;\n"
                + "uniform vec2 screenSize;\nuniform sampler2D depthtex0;\nuniform sampler2D depthtex1;\n"
                + storage + "\nvoid main() {"
                + " uvec2 coord = gl_GlobalInvocationID.xy; uvec2 size = uvec2(screenSize);"
                + " if (!flw_oitActive || any(greaterThanEqual(coord, size))) return;"
                + " uint pixel = coord.y * size.x + coord.x;"
                + " float cut = texelFetch(depthtex0, ivec2(coord), 0).x;"
                + " float opaque = texelFetch(depthtex1, ivec2(coord), 0).x;"
                + " uint demand = flw_lightSimpleEnabled && (_FLW_REVERSED_DEPTH"
                + " ? cut <= 1.0 && cut > opaque && opaque >= 0.0 : cut >= 0.0 && cut < opaque && opaque <= 1.0)"
                + " ? FLW_LIGHT_SIMPLE_CLASSIFIED : FLW_LIGHT_COMPLEX;"
                + " if (" + materialHeads + "[pixel] != 0xffffffffu) demand = 7u;"
                + " if (" + lightHeads + "[pixel] != 0xffffffffu) demand |= FLW_LIGHT_COMPLEX;"
                + " for (uint node = " + lightHeads + "[pixel]; node != 0xffffffffu; node = flw_nodes[node].x)"
                + " if ((flw_nodes[node].w >> 16u) != 0u) { demand = 7u; break; }"
                + lightHeads + "[flw_lightDemandOffset(pixel, size)] = demand;"
                + sort + " }";
    }
}
