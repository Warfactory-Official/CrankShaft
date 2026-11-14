package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.engine.DeferredOitPool;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.vertices.IrisVertexFormats;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

final class SeusDeferredAdapter implements DeferredOitAdapter {
    private static final DeferredOitPool.Spec POOL = new DeferredOitPool.Spec(1, 2, 6, 16, 1 << 16, 1 << 18, 16);
    private static final DeferredOitPool.Spec FIXED_POOL = new DeferredOitPool.Spec(1, 2, 6, 16, 1 << 16, 1 << 18, 16,
            true);
    private static final Pattern VELOCITY_DECLARATION = Pattern.compile("\\battribute\\s+vec3\\s+at_velocity\\s*;");
    private final boolean motion;
    private final boolean particles;

    SeusDeferredAdapter(boolean motion, boolean particles) {
        this.motion = motion;
        this.particles = particles;
    }

    private boolean fixedParticles() {
        return particles && (VkContext.isVulkanHost() ? VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED
                : GlCompat.CAPABILITIES.GL_ARB_fragment_shader_interlock);
    }

    @Override
    public int[] storageBindings() {
        return new int[]{0, 1, 2, 6};
    }

    @Override
    public boolean capturesTerrain() {
        return true;
    }

    @Override
    public void stage(Path root, Set<String> present, Map<Path, String> overrides, List<AbsolutePackPath> added) {
        if (motion && IrisVertexFormats.ENTITY.getElements().stream()
                                              .noneMatch(element -> element.name().equals("at_velocity"))) {
            for (String path : present) {
                if (!path.endsWith("/gbuffers_entities.vsh")) continue;
                var vertex = AbsolutePackPath.fromAbsolutePath(path);
                var declaration = VELOCITY_DECLARATION.matcher(ContractPatches.staged(root, vertex, overrides));
                if (declaration.results().count() != 1)
                    throw new UnsupportedOperationException("SEUS entity velocity declaration changed");
                // Compat with Iris: ENTITY provides no velocity; an active input aliases another vertex attribute.
                overrides.put(vertex.resolved(root), declaration.replaceFirst("const vec3 at_velocity = vec3(0.0);"));
            }
        }
        for (String path : present) {
            if (!path.endsWith("/gbuffers_water.fsh")) continue;
            String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            AbsolutePackPath clear = AbsolutePackPath.fromAbsolutePath(dir + "begin99.csh");
            if (present.contains(clear.getPathString()))
                throw new UnsupportedOperationException("SEUS capture clear program is occupied");
            String source = DeferredOitProfile.resource("layer_clear.comp")
                                              .replace("_FLW_SNAPSHOT_BINDING",
                                                      Integer.toString(POOL.snapshotBinding()))
                                              .replaceAll("(?ms)^#ifndef FLW_LAYER_NO_LIGHTS\\R.*?^#endif\\R?", "");
            overrides.put(clear.resolved(root), SeusDeferredPatch.clear(source, fixedParticles()));
            added.add(clear);
            // Placeholders for the sort, generated once the pack's depth convention is known, and for
            // SeusRearReflection, generated from the first composite.
            for (String program : List.of("composite", "composite_a", "composite_b")) {
                AbsolutePackPath pass = AbsolutePackPath.fromAbsolutePath(dir + program + ".csh");
                if (present.contains(pass.getPathString()))
                    throw new UnsupportedOperationException("SEUS deferred compute program is occupied: " + pass);
                overrides.put(pass.resolved(root), SeusDeferredPatch.QUADRANT_PASS + "void main() {\n}\n");
                added.add(pass);
            }
        }
    }

    @Override
    public String storageProperties() {
        return """
                
                iris.features.optional = SSBO
                uniform.vec2.screenSize = vec2(viewWidth, viewHeight)
                bufferObject.0 = 4 true 1.0 1.0
                bufferObject.1 = 2097152
                bufferObject.2 = 144
                bufferObject.6 = 4 true 1.0 1.0
                """;
    }

    @Override
    public void rejected(ProgramSet programs) {
        programs.getCompute(ProgramArrayId.Begin)[99] = new ComputeSource[0];
        programs.getCompute(ProgramArrayId.Composite)[0] = new ComputeSource[0];
    }

    @Override
    public DeferredOitPool.Spec pool() {
        return fixedParticles() ? FIXED_POOL : POOL;
    }

    @Override
    public Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts) {
        boolean reversed = programs.getPack().flywheel$nativeDepth();
        var formats = programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings();
        if (formats.get(0).getInternalFormat() != InternalTextureFormat.RGBA8
                || formats.get(1).getInternalFormat() != InternalTextureFormat.RGBA16
                || formats.get(2).getInternalFormat() != InternalTextureFormat.RGBA16
                || formats.get(7).getInternalFormat() != InternalTextureFormat.RGBA16F
                || motion && formats.get(8).getInternalFormat() != InternalTextureFormat.RGB16F)
            throw new UnsupportedOperationException("SEUS material formats differ from the checked profile");
        Map<String, String> result = new HashMap<>();
        ProgramSource water = programs.get(ProgramId.Water).orElseThrow();
        result.put(water.getName(), SeusDeferredPatch.capture(water.getFragmentSource().orElseThrow(), motion, true,
                POOL.snapshotBinding()));
        ProgramSource translucent = contracts.get(ContractProgram.GBUFFERS_TRANSLUCENT);
        result.put(translucent.getName(),
                SeusDeferredPatch.capture(translucent.getFragmentSource().orElseThrow(), motion, true,
                        POOL.snapshotBinding()));
        if (particles) {
            ProgramSource textured = programs.get(ProgramId.Textured).orElseThrow();
            result.put(textured.getName(), fixedParticles()
                    ? SeusDeferredPatch.particles(textured.getFragmentSource().orElseThrow())
                    : SeusDeferredPatch.capture(textured.getFragmentSource().orElseThrow(), false, false,
                    POOL.snapshotBinding()));
        }
        ProgramSource[] composite = programs.getComposite(ProgramArrayId.Composite);
        int boundary = -1;
        for (int i = 0; i <= 1 && boundary < 0; i++)
            if (stage(composite, i) && SeusDeferredPatch.boundary(composite[i].getFragmentSource().orElseThrow()))
                boundary = i;
        if (boundary < 0 || !stage(composite, 4))
            throw new UnsupportedOperationException("SEUS deferred resolve stages changed");
        var stages = new ArrayList<SeusDeferredPatch.Stage>();
        for (int i : IntStream.concat(IntStream.rangeClosed(0, boundary), IntStream.of(4)).toArray()) {
            if (!stage(composite, i))
                throw new UnsupportedOperationException("Unsupported SEUS material stage composite" + i);
            stages.add(new SeusDeferredPatch.Stage(i, composite[i].getFragmentSource().orElseThrow(),
                    composite[i].getDirectives().getDrawBuffers()));
        }
        result.put(composite[boundary].getName(),
                SeusDeferredPatch.resolve(composite[boundary].getFragmentSource().orElseThrow(), boundary, stages,
                        reversed));
        String fragment = composite[0].getFragmentSource().orElseThrow();
        result.put("composite.csh", SeusDeferredPatch.sort(fixedParticles(), fragment, reversed));
        String vertex = composite[0].getVertexSource().orElseThrow();
        int[] buffers = composite[0].getDirectives().getDrawBuffers();
        result.put("composite_a.csh", SeusRearReflection.pass(fragment, vertex, buffers, true, reversed));
        result.put("composite_b.csh", SeusRearReflection.pass(fragment, vertex, buffers, false, reversed));
        return Map.copyOf(result);
    }

    private static boolean stage(ProgramSource[] composite, int index) {
        ProgramSource source = composite[index];
        return source != null && source.isValid() && source.getGeometrySource().isEmpty()
                && source.getTessControlSource().isEmpty() && source.getTessEvalSource().isEmpty()
                && source.getDirectives().getViewportScale().scale() == 1;
    }
}
