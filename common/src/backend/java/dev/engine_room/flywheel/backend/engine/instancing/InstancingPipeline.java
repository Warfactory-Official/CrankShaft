package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class InstancingPipeline {
    private static final String INSTANCE_BUFFER_NAME = "_flw_instances";

    private static final String LIGHT_LUT_NAME = "_flw_lightLut";
    private static final String LIGHT_SECTIONS_NAME = "_flw_lightSections";
    private static final Map<Identifier, VertexAssembly> VERTEX_ASSEMBLY = new HashMap<>();
    private static final Map<Identifier, FragmentAssembly> FRAGMENT_ASSEMBLY = new HashMap<>();
    private static final ShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> {
            VertexAssembly va = VERTEX_ASSEMBLY.get(id);
            yield id.getPath().contains("/embedded/")
                    ? RenderPassShaders.assembleInstancingEmbeddedVertex(va.type(), va.material(), va.debug())
                    : RenderPassShaders.assembleInstancingVertex(va.type(), va.material(), va.debug());
        }
        case FRAGMENT -> {
            FragmentAssembly fa = FRAGMENT_ASSEMBLY.get(id);
            yield RenderPassShaders.fragment(fa.light(), false, fa.material(), fa.smoothness(), fa.cutout(), fa.fog(),
                    fa.debug());
        }
    };
    private static final Map<PipelineKey, RenderPipeline> CACHE = new HashMap<>();

    private InstancingPipeline() {
    }

    public static RenderPipeline pipelineFor(Material material, InstanceType<?> instanceType) {
        return pipelineFor(material, instanceType, false);
    }

    public static RenderPipeline pipelineFor(Material material, InstanceType<?> instanceType, boolean embedded) {
        MaterialShaders shaders = material.shaders();
        LightShader light = material.light();
        CutoutShader cutout = material.cutout();
        FogShader fog = material.fog();
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        DebugMode debug = FrameUniforms.debugMode();

        Identifier vId = vertexId(instanceType, embedded, shaders, debug != DebugMode.OFF);
        VERTEX_ASSEMBLY.putIfAbsent(vId, new VertexAssembly(instanceType, shaders, debug != DebugMode.OFF));
        Identifier fId = fragmentId(light, shaders, smoothness, cutout, fog, debug);
        FRAGMENT_ASSEMBLY.putIfAbsent(fId, new FragmentAssembly(light, shaders, smoothness, cutout, fog, debug));

        PipelineKey key = new PipelineKey(instanceType, light, shaders, smoothness, cutout, fog, debug,
                material.transparency(), material.depthTest(),
                material.writeMask().depth(), material.writeMask().color(),
                material.backfaceCulling(), material.polygonOffset(), embedded);
        RenderPipeline pipeline = CACHE.computeIfAbsent(key, InstancingPipeline::build);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static Identifier vertexId(InstanceType<?> instanceType, boolean embedded, MaterialShaders materialShaders,
                                       boolean debug) {
        return ResourceUtil.rl("codegen/instancing/" + (embedded ? "embedded/" : "")
                + ResourceUtil.toDebugFileNameNoExtension(instanceType.vertexShader())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                + (debug ? "__debug" : ""));
    }

    private static Identifier fragmentId(LightShader light, MaterialShaders materialShaders, LightSmoothness smoothness,
                                         CutoutShader cutout, FogShader fog, DebugMode debug) {
        return ResourceUtil.rl("codegen/instancing_frag/" + ResourceUtil.toDebugFileNameNoExtension(light.source())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(cutout.source())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(fog.source())
                + "__" + smoothness.getSerializedName()
                + (debug == DebugMode.OFF ? "" : "__debug_" + debug.getSerializedName()));
    }

    private static RenderPipeline build(PipelineKey key) {
        BindGroupLayout.Builder bindGroup = BindGroupLayout.builder()
                                                           .withSampler("Sampler0")
                                                           .withSampler("Sampler1")
                                                           .withSampler("Sampler2")
                                                           .withUniform(INSTANCE_BUFFER_NAME, UniformType.TEXEL_BUFFER,
                                                                   GpuFormat.RGBA32_UINT)
                                                           .withUniform(LIGHT_LUT_NAME, UniformType.TEXEL_BUFFER,
                                                                   GpuFormat.R32_UINT)
                                                           .withUniform(LIGHT_SECTIONS_NAME, UniformType.TEXEL_BUFFER,
                                                                   GpuFormat.R32_UINT)
                                                           .withUniform("_FlwInstanceDraw", UniformType.UNIFORM_BUFFER)
                                                           .withUniform("_FlwRenderOrigin", UniformType.UNIFORM_BUFFER);
        if (key.embedded()) {
            bindGroup.withUniform("_FlwEmbed", UniformType.UNIFORM_BUFFER);
        }

        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(
                                                               ResourceUtil.rl("pipeline/instanced/" + key.cacheName()))
                                                       .withVertexShader(vertexId(key.instanceType(), key.embedded(),
                                                               key.materialShaders(), key.debug() != DebugMode.OFF))
                                                       .withFragmentShader(
                                                               fragmentId(key.light(), key.materialShaders(),
                                                                       key.smoothness(), key.cutout(), key.fog(),
                                                                       key.debug()))
                                                       .withVertexBinding(0, InternalVertex.VERTEX_FORMAT)
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       // Positive offset wins GREATER_THAN_OR_EQUAL under reversed-Z (26.2 feeds bias straight to
                                                       // glPolygonOffset, no sign flip). OPAQUE offsets are units-only: slope terms beat real geometry
                                                       // at grazing angles; non-opaque (entity shadow decal) keeps the slope.
                                                       .withDepthStencilState(
                                                               new DepthStencilState(key.depthTest().compareOp,
                                                                       key.depthWrite(),
                                                                       key.polygonOffset() && key.transparency() != Transparency.OPAQUE ? 1.0f : 0.0f,
                                                                       key.polygonOffset() ? 10.0f : 0.0f))
                                                       .withCull(key.cull())
                                                       .withBindGroupLayout(bindGroup.build());

        ColorTargetState colorTarget = colorTarget(key);
        if (colorTarget != null) {
            builder.withColorTargetState(colorTarget);
        }
        return builder.build();
    }

    private static ColorTargetState colorTarget(PipelineKey key) {
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

    private record VertexAssembly(InstanceType<?> type, MaterialShaders material, boolean debug) {
    }

    private record FragmentAssembly(LightShader light, MaterialShaders material, LightSmoothness smoothness,
                                    CutoutShader cutout, FogShader fog, DebugMode debug) {
    }

    private record PipelineKey(InstanceType<?> instanceType, LightShader light, MaterialShaders materialShaders,
                               LightSmoothness smoothness, CutoutShader cutout, FogShader fog, DebugMode debug,
                               Transparency transparency, DepthTest depthTest,
                               boolean depthWrite, boolean colorWrite, boolean cull, boolean polygonOffset,
                               boolean embedded) {
        String cacheName() {
            StringBuilder sb = new StringBuilder();
            sb.append(ResourceUtil.toDebugFileNameNoExtension(instanceType.vertexShader()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(light.source()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(cutout.source()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(fog.source()))
              .append('_')
              .append(smoothness.getSerializedName())
              .append('_')
              .append(transparency.name())
              .append('_')
              .append(depthTest.name());
            if (debug != DebugMode.OFF) {
                sb.append("_debug_")
                  .append(debug.getSerializedName());
            }
            if (depthWrite) {
                sb.append("_dw");
            }
            if (!colorWrite) {
                sb.append("_nocw");
            }
            if (cull) {
                sb.append("_cull");
            }
            if (polygonOffset) {
                sb.append("_po");
            }
            if (embedded) {
                sb.append("_embed");
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        }
    }
}
