package dev.engine_room.flywheel.iris.compile.patches;

import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

/**
 * Rear-surface reflections for SEUS PTGI, ahead of the boundary pass: compute passes over the half-resolution lists,
 * built from the first composite, which traces the reflection. Records carry it in their third slot.
 */
final class SeusRearReflection {
    static final String SLOT = "2u";

    private SeusRearReflection() {
    }

    /**
     * {@code trace}: every captured surface behind a pixel's native one traces its reflection with the first
     * composite's packed-HDR branch; otherwise each trace is filtered over neighbouring surfaces on its plane.
     * {@code fragment}/{@code vertex}/{@code buffers}: the first composite.
     */
    static String pass(String fragment, String vertex, int[] buffers, boolean trace, boolean reversed) {
        return transform(DeferredLodDepth.seus(fragment, reversed), (tree, root, parser) -> {
            var main = function(root, "main");
            var hdr = SeusDeferredPatch.hdrBranch(main, root, "");
            if (hdr == null) throw unsupported("SEUS trace branch");
            RearReflectionCompute.fragmentPosition(tree, root, parser);
            ShaderDeclarations.uniform(tree, root, parser, "vec2", "screenSize");
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            if (trace) SeusDeferredPatch.trace(tree, root, parser, hdr, buffers);
            else SeusDeferredPatch.rearDecodes(tree, root, parser, hdr);
            main.detachAndDelete();
            if (trace) RearReflectionCompute.vertexInputs(tree, root, parser, vertex);
            String pixelGlobals = RearReflectionCompute.pixelGlobals(tree);
            RearReflectionCompute.computeStage(tree, root, parser);
            inject(tree, parser, trace
                            ? DeferredOitProfile.resource("seus_rear_trace.glsl").replace("_FLW_PIXEL_GLOBALS", pixelGlobals)
                                                .replace("texelFetch(depthtex0, pixel, 0).r",
                                                        DeferredLodDepth.front(root, "pixel"))
                                                .replace("texelFetch(depthtex1, pixel, 0).r",
                                                        DeferredLodDepth.opaque(root, "pixel"))
                            : RearReflectionCompute.filter("flw_heads", SLOT,
                                                           "GetViewPosition((vec2(pixel) + 0.5) / screenSize, uintBitsToFloat(flw_nodes[node].y)).xyz",
                                                           "normalize(_flw_rearGetGBufferDataTransparent((vec2(pixel) + 0.5) / screenSize, node, 1.0)"
                                                                   + ".geoNormal)",
                                                           DeferredLodDepth.opaque(root, "pixel"))
                                                   .replace("texelFetch(depthtex0, pixel, 0).x",
                                                           DeferredLodDepth.front(root, "pixel"))
                                                   .replace("texelFetch(depthtex0, neighbour, 0).x",
                                                           DeferredLodDepth.front(root, "neighbour")),
                    ASTInjectionPoint.END);
        });
    }
}
