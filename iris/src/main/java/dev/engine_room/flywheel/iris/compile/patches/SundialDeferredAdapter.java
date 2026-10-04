package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.engine.DeferredReplayPlan;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SundialDeferredAdapter implements DeferredOitAdapter {
    private static String sortCompute() {
        return DeferredOitProfile.resource("layer_sort.comp")
                                 .replace("_FLW_LAYER_STORAGE", DeferredOitProfile.resource("layer_storage.glsl"))
                                 .replace("_FLW_LAYER_LIGHT", DeferredOitProfile.resource("layer_light.glsl"))
                                 .replace("_FLW_LAYER_SORT", SundialDeferredPatch.materialSort()
                                                                                 .replace("_FLW_VISIBILITY_BODY",
                                                                                         "float transparentDensity = texelFetch(colortex5, ivec2(flw_coord), 0).a;")
                                                                                 .replace("_FLW_SORT_PIXEL",
                                                                                         "gl_GlobalInvocationID.xy")
                                                                                 .replace("_FLW_SORT_COUNTS_LAYERS",
                                                                                         "true")
                                                                                 .replace("_FLW_SORT_LAYER_BIAS", "0u")
                                                                                 .replace(" * 2u",
                                                                                         "") + SundialDeferredPatch.lightSort(
                                         "gl_GlobalInvocationID.xy"));
    }

    @Override
    public int[] storageBindings() {
        return new int[]{0, 1, 2, 5};
    }

    @Override
    public boolean capturesTerrain() {
        return true;
    }

    @Override
    public void stage(Path root, Set<String> present, Map<Path, String> overrides, List<AbsolutePackPath> added) {
        for (String path : present) {
            if (!path.endsWith("/gbuffers_water.fsh")) continue;
            String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            AbsolutePackPath clear = AbsolutePackPath.fromAbsolutePath(dir + "begin99.csh");
            if (present.contains(clear.getPathString()))
                throw new IllegalStateException("Deferred clear program is occupied");
            overrides.put(clear.resolved(root), DeferredOitProfile.resource("layer_clear.comp"));
            added.add(clear);
            if (present.contains(dir + "composite.fsh")) continue;
            AbsolutePackPath sort = AbsolutePackPath.fromAbsolutePath(dir + "composite.csh");
            if (present.contains(sort.getPathString()))
                throw new IllegalStateException("Deferred sort program is occupied");
            overrides.put(sort.resolved(root), sortCompute());
            added.add(sort);
        }
    }

    @Override
    public String storageProperties() {
        return """
                
                iris.features.optional = SSBO
                bufferObject.0 = 4 true 1.0 1.0
                bufferObject.1 = 2097152
                bufferObject.2 = 16
                bufferObject.5 = 4 true 1.0 1.0
                """;
    }

    @Override
    public void rejected(ProgramSet programs) {
        programs.getCompute(ProgramArrayId.Begin)[99] = new ComputeSource[0];
        ProgramSource first = programs.getComposite(ProgramArrayId.Composite)[0];
        if (first == null || !first.isValid()) programs.getCompute(ProgramArrayId.Composite)[0] = new ComputeSource[0];
    }

    @Override
    public @Nullable DeferredReplayPlan replayPlan(IrisRenderingPipeline pipeline, List<?> passes) {
        return SundialReplayResources.plan(((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets(), passes);
    }

    @Override
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
        ProgramSource unlit = contracts.get(ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT);
        result.put(unlit.getName(), SundialDeferredPatch.captureLight(unlit.getFragmentSource().orElseThrow(), false));
        for (ContractProgram id : List.of(ContractProgram.GBUFFERS_ADDITIVE,
                ContractProgram.GBUFFERS_NATIVE_ADDITIVE, ContractProgram.GBUFFERS_NATIVE_ADDITIVE_COLOR)) {
            ProgramSource emission = contracts.get(id);
            result.put(emission.getName(), SundialDeferredPatch.captureLight(emission.getFragmentSource().orElseThrow(),
                    id != ContractProgram.GBUFFERS_ADDITIVE));
        }
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
        for (int index : new int[]{0, 1, 2, 3, 6}) {
            ProgramSource source = composite[index];
            if (source != null && source.isValid()) {
                result.put(source.getName(),
                        SundialDeferredPatch.composite(index, source.getFragmentSource().orElseThrow()));
            }
        }
        return Map.copyOf(result);
    }
}
