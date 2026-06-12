package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class IndirectPipeline {
    private static final Map<Identifier, VertexAssembly> VERTEX_ASSEMBLY = new HashMap<>();
    private static final Map<Identifier, FragmentAssembly> FRAGMENT_ASSEMBLY = new HashMap<>();
    private static final ShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> {
            VertexAssembly va = VERTEX_ASSEMBLY.get(id);
            yield RenderPassShaders.assembleUberIndirectVertex(va.material(), va.debug(),
                    RenderPassShaders.maybeBindlessGl());
        }
        case FRAGMENT -> {
            FragmentAssembly fa = FRAGMENT_ASSEMBLY.get(id);
            yield RenderPassShaders.uberFragment(fa.light(), fa.material(), fa.smoothness(), fa.debug(),
                    RenderPassShaders.maybeBindlessGl());
        }
    };
    private static final Map<UberKey, RenderPipeline> UBER_CACHE = new HashMap<>();

    private IndirectPipeline() {
    }

    public static RenderPipeline uberPipelineFor(Material material) {
        return uberPipelineFor(material, material.writeMask().color());
    }

    public static RenderPipeline uberDepthOnlyPipelineFor(Material material) {
        return uberPipelineFor(material, false);
    }

    private static RenderPipeline uberPipelineFor(Material material, boolean colorWrite) {
        MaterialShaders shaders = material.shaders();
        LightShader light = material.light();
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        DebugMode debug = FrameUniforms.debugMode();

        Identifier vId = uberVertexId(shaders, debug != DebugMode.OFF);
        VERTEX_ASSEMBLY.putIfAbsent(vId, new VertexAssembly(shaders, debug != DebugMode.OFF));
        Identifier fId = uberFragmentId(light, shaders, smoothness, debug);
        FRAGMENT_ASSEMBLY.putIfAbsent(fId, new FragmentAssembly(light, shaders, smoothness, debug));

        UberKey key = new UberKey(light, shaders, smoothness, debug, material.transparency(), material.depthTest(),
                material.writeMask().depth(), colorWrite, material.backfaceCulling(), material.polygonOffset(),
                InstanceTypeIds.snapshot().types().size(),
                MaterialShaderIndices.cutoutSources().all().size(),
                MaterialShaderIndices.fogSources().all().size());
        RenderPipeline pipeline = UBER_CACHE.computeIfAbsent(key, IndirectPipeline::buildUber);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static Identifier uberVertexId(MaterialShaders materialShaders, boolean debug) {
        return ResourceUtil.rl("codegen/indirect/uber/g" + InstanceTypeIds.snapshot().types().size()
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                + (debug ? "__debug" : ""));
    }

    private static Identifier uberFragmentId(LightShader light, MaterialShaders materialShaders,
                                             LightSmoothness smoothness, DebugMode debug) {
        return ResourceUtil.rl("codegen/indirect_frag/uber/g" + MaterialShaderIndices.cutoutSources().all().size()
                + "_" + MaterialShaderIndices.fogSources().all().size()
                + "__" + ResourceUtil.toDebugFileNameNoExtension(light.source())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                + "__" + smoothness.getSerializedName()
                + (debug == DebugMode.OFF ? "" : "__debug_" + debug.getSerializedName()));
    }

    private static RenderPipeline buildUber(UberKey key) {
        BindGroupLayout.Builder bindGroup = BindGroupLayout.builder();
        if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
            bindGroup.withSampler("Sampler0");
        }
        bindGroup.withSampler("Sampler1")
                 .withSampler("Sampler2")
                 .withUniform("_FlwRenderOrigin", UniformType.UNIFORM_BUFFER);

        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(ResourceUtil.rl(
                                                               "pipeline/indirect/uber_" + key.uberCacheName()))
                                                       .withVertexShader(uberVertexId(key.materialShaders(),
                                                               key.debug() != DebugMode.OFF))
                                                       .withFragmentShader(
                                                               uberFragmentId(key.light(), key.materialShaders(),
                                                                       key.smoothness(), key.debug()))
                                                       .withVertexBinding(0, InternalVertex.VERTEX_FORMAT)
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(
                                                               new DepthStencilState(key.depthTest().compareOp,
                                                                       key.depthWrite(),
                                                                       key.polygonOffset() && key.transparency() != Transparency.OPAQUE ? 1.0f : 0.0f,
                                                                       key.polygonOffset() ? 10.0f : 0.0f))
                                                       .withCull(key.cull())
                                                       .withBindGroupLayout(bindGroup.build());

        ColorTargetState colorTarget = uberColorTarget(key);
        if (colorTarget != null) {
            builder.withColorTargetState(colorTarget);
        }
        return builder.build();
    }

    private static ColorTargetState uberColorTarget(UberKey key) {
        BlendFunction blend = switch (key.transparency()) {
            case OPAQUE -> null;
            case ADDITIVE -> BlendFunction.ADDITIVE;
            case LIGHTNING -> BlendFunction.LIGHTNING;
            case GLINT -> BlendFunction.GLINT;
            case CRUMBLING, TRANSLUCENT, ORDER_INDEPENDENT -> BlendFunction.TRANSLUCENT;
        };

        if (blend == null && key.colorWrite()) {
            return null;
        }

        int writeMask = key.colorWrite() ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE;
        return new ColorTargetState(Optional.ofNullable(blend), GpuFormat.RGBA8_UNORM, writeMask);
    }

    private record VertexAssembly(MaterialShaders material, boolean debug) {
    }

    private record FragmentAssembly(LightShader light, MaterialShaders material, LightSmoothness smoothness,
                                    DebugMode debug) {
    }

    private record UberKey(LightShader light, MaterialShaders materialShaders, LightSmoothness smoothness,
                           DebugMode debug, Transparency transparency, DepthTest depthTest, boolean depthWrite,
                           boolean colorWrite,
                           boolean cull, boolean polygonOffset, int typeGen, int cutoutGen, int fogGen) {
        String uberCacheName() {
            return ResourceUtil.toDebugFileNameNoExtension(light.source())
                    + "_" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                    + "_" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                    + "_" + smoothness.getSerializedName()
                    + (debug == DebugMode.OFF ? "" : "_debug_" + debug.getSerializedName())
                    + "_" + transparency.name().toLowerCase(Locale.ROOT)
                    + "_" + depthTest.name().toLowerCase(Locale.ROOT)
                    + (depthWrite ? "_dw" : "") + (colorWrite ? "" : "_nc")
                    + (cull ? "_cull" : "") + (polygonOffset ? "_po" : "");
        }
    }
}
