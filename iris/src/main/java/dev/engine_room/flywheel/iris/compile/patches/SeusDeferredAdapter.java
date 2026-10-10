package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.engine.DeferredReplayPlan;
import dev.engine_room.flywheel.iris.engine.DeferredReplayShaders;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class SeusDeferredAdapter implements DeferredOitAdapter {
    private static final Pattern VELOCITY_DECLARATION = Pattern.compile("\\battribute\\s+vec3\\s+at_velocity\\s*;");
    private final boolean motion;

    SeusDeferredAdapter(boolean motion) {
        this.motion = motion;
    }

    @Override
    public int[] storageBindings() {
        return new int[]{0, 1, 2};
    }

    @Override
    public boolean capturesTerrain() {
        return true;
    }

    @Override
    public void stage(Path root, Set<String> present, Map<Path, String> overrides, List<AbsolutePackPath> added) {
        if (motion && IrisVertexFormats.ENTITY.getElements().stream().noneMatch(element -> element.name().equals("at_velocity"))) {
            for (String path : present) {
                if (!path.endsWith("/gbuffers_entities.vsh")) continue;
                Path vertex = AbsolutePackPath.fromAbsolutePath(path).resolved(root);
                try {
                    String source = Files.readString(vertex);
                    var declaration = VELOCITY_DECLARATION.matcher(source);
                    if (declaration.results().count() != 1)
                        throw new UnsupportedOperationException("SEUS entity velocity declaration changed");
                    // Compat with Iris: ENTITY provides no velocity; an active input aliases another vertex attribute.
                    overrides.put(vertex, declaration.replaceFirst("const vec3 at_velocity = vec3(0.0);"));
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }
        for (String path : present) {
            if (!path.endsWith("/gbuffers_water.fsh")) continue;
            String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            AbsolutePackPath clear = AbsolutePackPath.fromAbsolutePath(dir + "begin99.csh");
            if (present.contains(clear.getPathString()))
                throw new UnsupportedOperationException("SEUS capture clear program is occupied");
            overrides.put(clear.resolved(root), DeferredReplayShaders.source("layer_clear.comp",
                    compilation -> compilation.define("FLW_LAYER_NO_LIGHTS")));
            added.add(clear);
        }
    }

    @Override
    public String storageProperties() {
        return """

                iris.features.optional = SSBO
                uniform.vec2.screenSize = vec2(viewWidth, viewHeight)
                bufferObject.0 = 4 true 1.0 1.0
                bufferObject.1 = 2097152
                bufferObject.2 = 16
                """;
    }

    @Override
    public void rejected(ProgramSet programs) {
        programs.getCompute(ProgramArrayId.Begin)[99] = new ComputeSource[0];
    }

    @Override
    public @Nullable DeferredReplayPlan replayPlan(IrisRenderingPipeline pipeline, List<?> passes) {
        return SeusReplayResources.plan(((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets(), passes, motion);
    }

    @Override
    public Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts) {
        var formats = programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings();
        if (formats.get(0).getInternalFormat() != InternalTextureFormat.RGBA8
                || formats.get(1).getInternalFormat() != InternalTextureFormat.RGBA16
                || formats.get(2).getInternalFormat() != InternalTextureFormat.RGBA16
                || formats.get(7).getInternalFormat() != InternalTextureFormat.RGBA16F
                || motion && formats.get(8).getInternalFormat() != InternalTextureFormat.RGB16F)
            throw new UnsupportedOperationException("SEUS material formats differ from the checked profile");
        Map<String, String> result = new HashMap<>();
        ProgramSource water = programs.get(ProgramId.Water).orElseThrow();
        result.put(water.getName(), SeusDeferredPatch.capture(water.getFragmentSource().orElseThrow(), motion, true));
        ProgramSource translucent = contracts.get(ContractProgram.GBUFFERS_TRANSLUCENT);
        result.put(translucent.getName(), SeusDeferredPatch.capture(translucent.getFragmentSource().orElseThrow(), motion, true));
        ProgramSource textured = programs.get(ProgramId.Textured).orElse(null);
        if (textured != null && textured.isValid() && Arrays.equals(textured.getDirectives().getDrawBuffers(), new int[]{1, 2}))
            result.put(textured.getName(), SeusDeferredPatch.capture(textured.getFragmentSource().orElseThrow(), false, false));
        ProgramSource[] composite = programs.getComposite(ProgramArrayId.Composite);
        for (int i = 0; i <= 4; i++) {
            ProgramSource source = composite[i];
            if (source == null || !source.isValid() || source.getGeometrySource().isPresent()
                    || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()
                    || source.getDirectives().getViewportScale().scale() != 1)
                throw new UnsupportedOperationException("Unsupported SEUS material stage composite" + i);
            result.put(source.getName(), SeusDeferredPatch.material(source.getFragmentSource().orElseThrow(), i));
        }
        return Map.copyOf(result);
    }
}
