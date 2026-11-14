package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.PipelineVersions;
import dev.engine_room.flywheel.backend.compile.ProgramGeneration;
import dev.engine_room.flywheel.backend.compile.ProgramVersions;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.compat.backend.PipelineShaderSource;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class FarLayerPipeline {
    private static final Map<Key, ProgramVersions<RenderPipeline>> VERSIONS = new HashMap<>();
    private static final Map<Identifier, Key> SOURCES = new HashMap<>();
    private static final PipelineShaderSource SOURCE = (id, type) -> {
        var key = SOURCES.get(id);
        return switch (type) {
            case VERTEX -> RenderPassShaders.assembleFarLayerCaptureVertex(key.shaders());
            case FRAGMENT -> RenderPassShaders.assembleFarLayerCaptureFragment(key.light(), key.shaders(),
                    key.smoothness());
        };
    };

    static @Nullable RenderPipeline get(Material material) {
        var key = new Key(material.light(), material.shaders(), BackendConfig.INSTANCE.lightSmoothness());
        return PipelineVersions.select(VERSIONS, key, ProgramGeneration.current(), FarLayerPipeline::request);
    }

    private static RenderPipeline request(Key key) {
        var generation = ProgramGeneration.current();
        String name = "codegen/far/layer_capture/g" + generation.types() + "_" + generation.cutouts()
                + "_" + generation.fogs() + "_" + ResourceUtil.toDebugFileNameNoExtension(key.light().source())
                + "_" + ResourceUtil.toDebugFileNameNoExtension(key.shaders().vertexSource())
                + "_" + ResourceUtil.toDebugFileNameNoExtension(key.shaders().fragmentSource())
                + "_" + key.smoothness().getSerializedName();
        var id = ResourceUtil.rl(name);
        SOURCES.put(id, key);
        var builder = IndirectPipeline.stateBuilder(Transparency.OPAQUE, DepthTest.LEQUAL, false, false,
                                              false, false, GlCompat.SUPPORTS_BINDLESS_TEXTURES)
                                      .withLocation(ResourceUtil.rl("pipeline/" + name))
                                      .withVertexShader(id).withFragmentShader(id)
                                      .withDepthStencilState(
                                              new DepthStencilState(CompareOp.ALWAYS_PASS, false, 0.0f, 0.0f))
                                      .withColorTargetState(new ColorTargetState(Optional.empty(),
                                              GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE));
        var pipeline = builder.build();
        RenderPipelineCompiler.request(pipeline, SOURCE, null);
        return pipeline;
    }

    private record Key(LightShader light, MaterialShaders shaders, LightSmoothness smoothness) {
    }
}
