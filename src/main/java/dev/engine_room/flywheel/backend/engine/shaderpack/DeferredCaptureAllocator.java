package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import dev.engine_room.flywheel.backend.gl.GlCompat;

final class DeferredCaptureAllocator {
    private DeferredCaptureAllocator() {
    }

    static String coalesce(String source) {
        var caps = GlCompat.CAPABILITIES;
        if (!caps.GL_ARB_shader_ballot || !caps.GL_ARB_gpu_shader_int64) return source;
        var ast = GlslAst.parse(source);
        ast.function("flw_reserveSlots").setBody((CompoundStatement) ast.statement("""
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
        ast.extension("GL_ARB_shader_ballot");
        ast.extension("GL_ARB_gpu_shader_int64");
        return ast.source();
    }
}
