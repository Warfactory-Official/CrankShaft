package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;

/**
 * SEUS capture-list ordering and visibility for both Iris APIs.
 */
public final class SeusReplayShaders {
    private SeusReplayShaders() {
    }

    public static void sort(Compilation compilation) {
        String sort = DeferredOitProfile.resource("layer_sort.glsl")
                                        .replace(
                                                "flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);",
                                                "")
                                        .replace("_FLW_VISIBILITY_BODY",
                                                "if (uintBitsToFloat(flw_nodes[flw_node].y) < flw_opaqueDepth) ++flw_visible;")
                                        .replace("_FLW_SORT_PIXEL", "pixel")
                                        .replace("_FLW_SORT_COUNTS_LAYERS", "true")
                                        .replace("_FLW_SORT_LAYER_BIAS", "0u")
                                        .replace("flw_length + 0u", "flw_visible")
                                        .replace(" * 2u", "");
        // HRR 3 uses the final word for motion, so every bit participates in an equal-depth tie.
        String storage = DeferredOitProfile.resource("layer_storage.glsl")
                                           .replace("return (a.w & FLW_LAYER_UNLIT) <= (b.w & FLW_LAYER_UNLIT);",
                                                   "return a.w <= b.w;");
        compilation.appendComponent(new ShaderAssembly.RawSource("seus_sort_storage",
                "uniform vec2 screenSize;\nuniform sampler2D opaqueDepth;\n" + storage));
        compilation.appendComponent(new ShaderAssembly.RawSource("seus_sort_lists",
                "void flw_sortSeus(uvec2 pixel) {\nuint flw_visible = 0u;\n"
                        + "float flw_opaqueDepth = 1.0 - texelFetch(opaqueDepth, ivec2(pixel), 0).r;\n"
                        + sort + "\n}"));
    }
}
