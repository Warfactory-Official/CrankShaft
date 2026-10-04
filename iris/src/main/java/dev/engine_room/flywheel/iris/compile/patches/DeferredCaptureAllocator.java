package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

final class DeferredCaptureAllocator {
    private DeferredCaptureAllocator() {
    }

    static void coalesce(TranslationUnit tree, ASTParser parser) {
        var caps = GlCompat.CAPABILITIES;
        if (!caps.GL_ARB_shader_ballot || !caps.GL_ARB_gpu_shader_int64) return;
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "#extension GL_ARB_shader_ballot : require\n",
                "#extension GL_ARB_gpu_shader_int64 : require\n");
        var allocator = tree.getRoot().nodeIndex.getStream(FunctionDefinition.class)
                                                .filter(definition -> definition.getFunctionPrototype().getName()
                                                                                .getName().equals("flw_reserveSlots"))
                                                .findFirst().orElseThrow();
        allocator.getBody().replaceByAndDelete(parser.parseStatement(tree.getRoot(), """
                {
                    uint64_t lanes = ballotARB(true);
                    uvec2 words = unpackUint2x32(lanes);
                    uint leader = words.x != 0u ? uint(findLSB(words.x)) : 32u + uint(findLSB(words.y));
                    ivec2 count = bitCount(words);
                    uint base = 0u;
                    if (gl_SubGroupInvocationARB == leader) base = atomicAdd(flw_count, slots * uint(count.x + count.y));
                    base = readInvocationARB(base, leader);
                    ivec2 rank = bitCount(unpackUint2x32(lanes & gl_SubGroupLtMaskARB));
                    return base + slots * uint(rank.x + rank.y);
                }
                """));
    }
}
