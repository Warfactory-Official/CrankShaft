package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.engine.DeferredOitPool;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SundialDeferredAdapter implements DeferredOitAdapter {
    private static final DeferredOitPool.Spec POOL = new DeferredOitPool.Spec(1, 2, 6, 16, 1 << 16, 1 << 18, 16);

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
        return new int[]{0, 1, 2, 5, 6};
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
            overrides.put(clear.resolved(root), DeferredOitProfile.resource("layer_clear.comp")
                                                                  .replace("_FLW_SNAPSHOT_BINDING",
                                                                          Integer.toString(POOL.snapshotBinding())));
            added.add(clear);
            // Placeholders for SundialRearReflection, generated from the resolve and reflection passes, and for the
            // sort, generated once the pack's depth convention is known.
            for (String program : List.of("composite1", "composite1_a")) {
                AbsolutePackPath reflection = AbsolutePackPath.fromAbsolutePath(dir + program + ".csh");
                if (present.contains(reflection.getPathString()))
                    throw new IllegalStateException("Rear reflection program is occupied: " + reflection);
                overrides.put(reflection.resolved(root), DeferredOitProfile.resource("layer_rear_pass.comp"));
                added.add(reflection);
            }
            if (present.contains(dir + "composite.fsh")) continue;
            AbsolutePackPath sort = AbsolutePackPath.fromAbsolutePath(dir + "composite.csh");
            if (present.contains(sort.getPathString()))
                throw new IllegalStateException("Deferred sort program is occupied");
            overrides.put(sort.resolved(root), DeferredOitProfile.resource("layer_rear_pass.comp"));
            added.add(sort);
        }
    }

    @Override
    public String storageProperties() {
        return """
                
                iris.features.optional = SSBO
                bufferObject.0 = 4 true 1.0 1.0
                bufferObject.1 = 2097152
                bufferObject.2 = 144
                bufferObject.5 = 28 true 1.0 1.0
                bufferObject.6 = 4 true 1.0 1.0
                """;
    }

    @Override
    public void rejected(ProgramSet programs) {
        programs.getCompute(ProgramArrayId.Begin)[99] = new ComputeSource[0];
        programs.getCompute(ProgramArrayId.Composite)[1] = new ComputeSource[0];
        ProgramSource first = programs.getComposite(ProgramArrayId.Composite)[0];
        if (first == null || !first.isValid()) programs.getCompute(ProgramArrayId.Composite)[0] = new ComputeSource[0];
    }

    @Override
    public DeferredOitPool.Spec pool() {
        return POOL;
    }

    @Override
    public Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts) {
        boolean reversed = programs.getPack().flywheel$nativeDepth();
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
        result.put(water.getName(),
                SundialDeferredPatch.capture(water.getFragmentSource().orElseThrow(), POOL.snapshotBinding()));
        ProgramSource contract = contracts.get(ContractProgram.GBUFFERS_TRANSLUCENT);
        result.put(contract.getName(),
                SundialDeferredPatch.capture(contract.getFragmentSource().orElseThrow(), POOL.snapshotBinding()));
        ProgramSource unlit = contracts.get(ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT);
        result.put(unlit.getName(), SundialDeferredPatch.captureLight(unlit.getFragmentSource().orElseThrow(), false,
                POOL.snapshotBinding()));
        for (ContractProgram id : List.of(ContractProgram.GBUFFERS_ADDITIVE,
                ContractProgram.GBUFFERS_NATIVE_ADDITIVE, ContractProgram.GBUFFERS_NATIVE_ADDITIVE_COLOR)) {
            ProgramSource emission = contracts.get(id);
            result.put(emission.getName(), SundialDeferredPatch.captureLight(emission.getFragmentSource().orElseThrow(),
                    id != ContractProgram.GBUFFERS_ADDITIVE, POOL.snapshotBinding()));
        }
        ProgramSource[] composite = programs.getComposite(ProgramArrayId.Composite);
        for (int index : new int[]{1, 3, 6}) {
            if (composite[index] == null || !composite[index].isValid()) {
                throw new UnsupportedOperationException("Missing deferred resolve program composite" + index);
            }
        }
        var computes = programs.getCompute(ProgramArrayId.Composite);
        if (composite[0] == null || !composite[0].isValid()) {
            if (computes.length == 0 || computes[0][0] == null)
                throw new UnsupportedOperationException("Missing deferred layer sort");
            result.put("composite.csh", sortCompute());
        }
        for (int index : new int[]{0, 1, 6}) {
            ProgramSource source = composite[index];
            if (source != null && source.isValid()) {
                result.put(source.getName(),
                        SundialDeferredPatch.composite(index, source.getFragmentSource().orElseThrow(),
                                composite[3].getFragmentSource().orElseThrow(), reversed));
            }
        }
        String reflection = composite[3].getFragmentSource().orElseThrow();
        if (SundialDeferredPatch.reflects(reflection)) {
            String resolve = composite[1].getFragmentSource().orElseThrow();
            String vertex = composite[1].getVertexSource().orElseThrow();
            result.put("composite1.csh", SundialRearReflection.pass(resolve, vertex, reflection, true, reversed));
            result.put("composite1_a.csh", SundialRearReflection.pass(resolve, vertex, reflection, false, reversed));
        }
        return Map.copyOf(result);
    }
}
