package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.iris.compile.ContractProgram;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public enum DeferredOitProfile {
    SUNDIAL;

    public static boolean enabled() {
        var caps = GlCompat.CAPABILITIES;
        return Boolean.parseBoolean(System.getProperty("crankshaft.iris.oit.deferred", "true")) && caps != null
                && caps.glDispatchCompute != 0 && caps.glDrawElementsIndirect != 0
                && caps.glCreateBuffers != 0 && caps.glNamedBufferStorage != 0
                && caps.glCreateTextures != 0 && caps.glCopyImageSubData != 0;
    }

    public static String resource(String name) {
        try (var stream = DeferredOitProfile.class.getResourceAsStream("/assets/flywheel/iris/patches/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Pass-0 sort for a dimension without composite0 (Sundial's Nether, End): no cloud visibility pass, so every layer
     * keeps the pixel's colortex5 alpha.
     */
    public static String sortCompute() {
        return resource("layer_sort.comp").replace("_FLW_LAYER_STORAGE", resource("layer_storage.glsl"))
                                          .replace("_FLW_LAYER_SORT", resource("layer_sort.glsl")
                                                  .replace("_FLW_VISIBILITY_BODY",
                                                          "float transparentDensity = texelFetch(colortex5, ivec2(flw_coord), 0).a;")
                                                  .replace("_FLW_SORT_PIXEL", "gl_GlobalInvocationID.xy"));
    }

    public Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts) {
        var formats = programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings();
        if ((formats.get(0).getInternalFormat() != InternalTextureFormat.RGBA8
                && formats.get(0).getInternalFormat() != InternalTextureFormat.RGBA)
                || formats.get(1).getInternalFormat() != InternalTextureFormat.RGBA16_SNORM
                || formats.get(2).getInternalFormat() != InternalTextureFormat.RGBA16
                || formats.get(4).getInternalFormat() != InternalTextureFormat.RGBA16F
                || formats.get(5).getInternalFormat() != InternalTextureFormat.RGBA16F) {
            throw new UnsupportedOperationException("Deferred material formats differ from the checked profile");
        }
        Map<String, String> result = new HashMap<>();
        ProgramSource water = programs.get(ProgramId.Water).orElseThrow();
        result.put(water.getName(), SundialDeferredPatch.capture(water.getFragmentSource().orElseThrow()));
        ProgramSource contract = contracts.get(ContractProgram.GBUFFERS_TRANSLUCENT);
        result.put(contract.getName(), SundialDeferredPatch.capture(contract.getFragmentSource().orElseThrow()));
        ProgramSource[] composite = programs.getComposite(ProgramArrayId.Composite);
        for (int index = 0; index <= 6; index++) {
            ProgramSource source = composite[index];
            if (source == null || !source.isValid()) continue;
            var viewport = source.getDirectives().getViewportScale();
            if (viewport.scale() != 1 || viewport.viewportX() != 0 || viewport.viewportY() != 0
                    || source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent()
                    || source.getTessEvalSource().isPresent()) {
                throw new UnsupportedOperationException("Unsupported deferred layer stage " + source.getName());
            }
        }
        for (int index : new int[]{1, 3, 6}) {
            if (composite[index] == null || !composite[index].isValid()) {
                throw new UnsupportedOperationException("Missing deferred layer program composite" + index);
            }
        }
        var computes = programs.getCompute(ProgramArrayId.Composite);
        if ((composite[0] == null || !composite[0].isValid())
                && (computes.length == 0 || computes[0][0] == null)) {
            throw new UnsupportedOperationException("Missing deferred layer sort");
        }
        for (int index : new int[]{0, 1, 2, 6}) {
            ProgramSource source = composite[index];
            if (source != null && source.isValid()) {
                result.put(source.getName(),
                        SundialDeferredPatch.composite(index, source.getFragmentSource().orElseThrow()));
            }
        }
        return Map.copyOf(result);
    }
}
