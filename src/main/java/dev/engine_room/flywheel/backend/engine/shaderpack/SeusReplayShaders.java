package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;

final class SeusReplayShaders {
    private SeusReplayShaders() { }

    static String sort() {
        var sort = DeferredSortShader.build(DeferredSortShader.Mode.SEUS, "pixel",
                "if(uintBitsToFloat(flw_nodes[flw_node].y)<flw_opaqueDepth)++flw_visible;");
        var storage = GlslAst.parse(DeferredReplayShaders.resource("layer_storage.glsl"));
        // HRR 3 stores motion in the final word; equal-depth ordering must retain every bit.
        storage.replaceExpression("(a.w&FLW_LAYER_UNLIT)<=(b.w&FLW_LAYER_UNLIT)", "a.w<=b.w", true);
        return "uniform vec2 screenSize;uniform sampler2D opaqueDepth;\n" + storage.body()
                // 1.12.2: native targets retain conventional depth instead of Iris's reversed depth.
                + "void flw_sortSeus(uvec2 pixel){uint flw_visible=0u;float flw_opaqueDepth=texelFetch(opaqueDepth,ivec2(pixel),0).r;\n"
                + sort + "\n}\n";
    }
}
