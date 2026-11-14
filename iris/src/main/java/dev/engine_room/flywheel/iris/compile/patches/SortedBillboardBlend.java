package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;

/**
 * A frame-latched source-over path for an isolated, explicitly view-depth-sorted billboard population.
 */
public final class SortedBillboardBlend {
    private SortedBillboardBlend() {
    }

    public static boolean enabled() {
        return !IrisRuntime.EXPERIMENTAL && !VkContext.isVulkanHost()
                && GlCompat.MAX_SHADER_STORAGE_BUFFER_BINDINGS >= 22
                && Boolean.parseBoolean(System.getProperty("crankshaft.iris.sortedBillboardBlend", "true"));
    }

    public static boolean shardedDemand() {
        var caps = GlCompat.CAPABILITIES;
        return caps.GL_ARB_shader_ballot && caps.GL_ARB_gpu_shader_int64;
    }

    public static String applyFastFragment(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            var main = tree.getOneMainDefinitionBody();
            var capture = DeferredSources.calls(main, root, "flw_captureLight").getFirst();
            var selection = capture.getAncestor(SelectionStatement.class);
            String predicate = ASTPrinter.printSimple(selection.getCondition());
            String value = ASTPrinter.printSimple(capture.getParameters().get(2));
            String count = "flw_reserveSlots(1u);";
            if (shardedDemand()) {
                DeferredSources.inject(tree, parser,
                        DeferredOitProfile.resource("sorted_billboard_blend.glsl")
                                          .replace("readonly buffer FlwSortedBlend", "buffer FlwSortedBlend"),
                        ASTInjectionPoint.BEFORE_DECLARATIONS);
                String pixel = ASTPrinter.printSimple(capture.getParameters().get(0));
                count = """
                        uint64_t flw_sortedLanes = ballotARB(true);
                        uvec2 flw_sortedLaneWords = unpackUint2x32(flw_sortedLanes);
                        uint flw_sortedLeader = flw_sortedLaneWords.x != 0u
                                ? uint(findLSB(flw_sortedLaneWords.x)) : 32u + uint(findLSB(flw_sortedLaneWords.y));
                        if (gl_SubGroupInvocationARB == flw_sortedLeader) {
                            uvec2 flw_sortedPixel = uvec2(%s);
                            uint flw_sortedHash = flw_sortedPixel.x * 0x9e3779b9u ^ flw_sortedPixel.y * 0x85ebca6bu;
                            flw_sortedHash ^= flw_sortedHash >> 16u;
                            ivec2 flw_sortedCounts = bitCount(flw_sortedLaneWords);
                            atomicAdd(flw_sortedBlendDemand[flw_sortedHash & 127u],
                                    uint(flw_sortedCounts.x + flw_sortedCounts.y));
                        }
                        """.formatted(pixel);
            }
            selection.replaceByAndDelete(parser.parseStatement(root, """
                    {
                        vec4 flw_sortedBlendValue = %s;
                        if (!flw_oitActive || !(%s) || all(equal(flw_sortedBlendValue, vec4(0.0)))) discard;
                        %s
                        guestUnlit = vec4(unpackHalf2x16(packHalf2x16(flw_sortedBlendValue.rg)),
                                unpackHalf2x16(packHalf2x16(flw_sortedBlendValue.ba)));
                    }
                    """.formatted(value, predicate, count)));
        });
        return parser.transform(source);
    }

    public static String skipIfFast(String computeSource) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            DeferredSources.inject(tree, parser, DeferredOitProfile.resource("sorted_billboard_blend.glsl"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            root.rename("main", "_flw_sortedBlendComputeMain");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END, """
                    void main() {
                        if (flw_sortedBlendActive != 0u) return;
                        _flw_sortedBlendComputeMain();
                    }
                    """);
        });
        return parser.transform(computeSource);
    }

    /**
     * {@code reversed}: the pack takes raw reversed depth.
     */
    public static String latchSource(String existingQuerySource, boolean reversed) {
        String reduceDemand = shardedDemand() ? """
                if (gl_LocalInvocationIndex == 0u && flw_sortedBlendActive != 0u) {
                    uint demand = 0u;
                    for (uint i = 0u; i < 128u; i++) demand += flw_sortedBlendDemand[i];
                    flw_count = demand;
                }
                """ : "";
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            ShaderDeclarations.uniform(tree, root, parser, "sampler2D", "colortex18");
            DeferredSources.inject(tree, parser,
                    DeferredOitProfile.resource("sorted_billboard_blend.glsl")
                                      .replace("readonly buffer FlwSortedBlend", "coherent buffer FlwSortedBlend"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "layout(std430, binding = 9) readonly buffer FlwSortedDraws { uint flw_sortedDrawWords[]; };",
                    "layout(std430, binding = 10) readonly buffer FlwSortedRanges { uvec2 flw_sortedLightRanges[]; };",
                    "uniform uint flw_phase;", "uniform uint flw_lightRangeCount;");
            var demand = DeferredSources.function(root, "_flw_demandMain");
            for (var call : DeferredSources.calls(demand, root, "atomicOr")) {
                call.replaceByAndDelete(parser.parseExpression(root, "atomicAnd(flw_sortedBlendActive, 0u)"));
            }
            DeferredSources.function(root, "flw_resolveLight").getBody()
                           .replaceByAndDelete(parser.parseStatement(root, """
                                   {
                                       if ((_FLW_REVERSED_DEPTH ? front > back : front < back) && all(lessThan(uvec2(pixel), size))
                                               && (front != (_FLW_REVERSED_DEPTH ? 1.0 : 0.0) || back != flw_demandOpaque(pixel))) {
                                           atomicAnd(flw_sortedBlendActive, 0u);
                                       }
                                       return background;
                                   }
                                   """));
            DeferredSources.function(root, "flw_resolveRefractedLight").getBody()
                           .replaceByAndDelete(parser.parseStatement(root, """
                                   {
                                       ivec2 pixel = ivec2(coord * screenSize);
                                       if ((_FLW_REVERSED_DEPTH ? back < 1.0 : back > 0.0)
                                               && all(lessThan(uvec2(pixel), uvec2(screenSize)))) {
                                           atomicAnd(flw_sortedBlendActive, 0u);
                                       }
                                       return background;
                                   }
                                   """));
            root.rename("main", "_flw_sortedBlendQueryMain");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END, """
                    void main() {
                        if (flw_phase == 0u) {
                            if (gl_LocalInvocationIndex == 0u) {
                                flw_sortedBlendActive = flw_oitActive && flw_count == 0u && flw_overflow == 0u ? 1u : 0u;
                            }
                            for (uint i = gl_LocalInvocationIndex; i < 128u; i += 64u)
                                flw_sortedBlendDemand[i] = 0u;
                            memoryBarrierBuffer();
                            barrier();
                            for (uint i = gl_LocalInvocationIndex; i < flw_lightRangeCount; i += 64u) {
                                uvec2 range = flw_sortedLightRanges[i];
                                for (uint draw = range.x; draw < range.y; draw++) {
                                    if (flw_sortedDrawWords[draw * 12u + 1u] != 0u)
                                        atomicAnd(flw_sortedBlendActive, 0u);
                                }
                            }
                            return;
                        }
                        if (flw_phase == 2u) {
                            if (gl_LocalInvocationIndex == 0u) {
                                flw_pad = (flw_pad & 0x7fffffffu)
                                        | (flw_sortedBlendActive != 0u ? 0x80000000u : 0u);
                            }
                            return;
                        }
                        if (flw_phase == 3u) {
                            %s
                            return;
                        }
                        if (flw_sortedBlendActive == 0u) return;
                        uvec2 pixel = gl_GlobalInvocationID.xy;
                        if (any(greaterThanEqual(pixel, uvec2(screenSize)))) return;
                        if (any(notEqual(texelFetch(colortex18, ivec2(pixel), 0), vec4(0.0)))) {
                            atomicAnd(flw_sortedBlendActive, 0u);
                            return;
                        }
                        _flw_sortedBlendQueryMain();
                    }
                    """.formatted(reduceDemand));
        });
        return DeferredOitProfile.depthConvention(parser.transform(existingQuerySource), reversed);
    }
}
