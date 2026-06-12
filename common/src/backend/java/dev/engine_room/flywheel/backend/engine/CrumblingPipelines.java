package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
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

public final class CrumblingPipelines {
    // Vanilla RenderPipelines.CRUMBLING blend: color = src*DST_COLOR + dst*SRC_COLOR (= 2*src*dst), alpha = src.
    private static final BlendFunction CRUMBLING_BLEND = new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.SRC_COLOR,
            BlendFactor.ONE, BlendFactor.ZERO);

    // Type-independent crumbling fragment ids (one per backend x config light smoothness; the LightShader is
    // constant SMOOTH_WHEN_EMBEDDED). The per-type vertex ids map back to their instance type, and each fragment
    // id maps back to its (indirect, smoothness) for the (id, type)-keyed ShaderSource. Render-thread only.
    private static final Map<Identifier, InstanceType<?>> TYPE_BY_VERTEX_ID = new HashMap<>();
    private static final Map<Identifier, FragmentKey> FRAGMENT_KEY = new HashMap<>();
    private static final ShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> {
            InstanceType<?> instanceType = TYPE_BY_VERTEX_ID.get(id);
            boolean debug = id.getPath().endsWith("_debug");
            yield id.getPath().contains("/indirect/")
                    ? RenderPassShaders.assembleIndirectCrumblingVertex(instanceType, debug)
                    : RenderPassShaders.assembleInstancingCrumblingVertex(instanceType, debug);
        }
        case FRAGMENT -> {
            FragmentKey fk = FRAGMENT_KEY.get(id);
            yield RenderPassShaders.crumblingFragment(fk.indirect(), fk.smoothness(), fk.debug());
        }
    };
    private static final Map<Key, RenderPipeline> CACHE = new HashMap<>();

    private CrumblingPipelines() {
    }

    public static RenderPipeline pipeline(Material crumblingMaterial, InstanceType<?> instanceType, boolean indirect) {
        // Read once per request; a config change yields a new key + fragment id => cache miss => recompile next
        // frame (pipeline runs every frame from the crumbling draw loop). The debug mode rides the same way.
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        DebugMode debug = FrameUniforms.debugMode();
        Identifier vertexId = vertexId(instanceType, indirect, debug != DebugMode.OFF);
        TYPE_BY_VERTEX_ID.putIfAbsent(vertexId, instanceType);
        FRAGMENT_KEY.putIfAbsent(fragmentId(indirect, smoothness, debug), new FragmentKey(indirect, smoothness, debug));

        Key key = new Key(instanceType, indirect, smoothness, debug, crumblingMaterial.depthTest(),
                crumblingMaterial.backfaceCulling());
        RenderPipeline pipeline = CACHE.computeIfAbsent(key, CrumblingPipelines::build);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static Identifier vertexId(InstanceType<?> instanceType, boolean indirect, boolean debug) {
        return ResourceUtil.rl("codegen/crumbling/" + (indirect ? "indirect/" : "instancing/")
                + ResourceUtil.toDebugFileNameNoExtension(instanceType.vertexShader())
                + (debug ? "_debug" : ""));
    }

    private static Identifier fragmentId(boolean indirect, LightSmoothness smoothness, DebugMode debug) {
        return ResourceUtil.rl("codegen/crumbling_frag/" + (indirect ? "ind" : "ins")
                + "__" + smoothness.getSerializedName()
                + (debug == DebugMode.OFF ? "" : "__debug_" + debug.getSerializedName()));
    }

    private static RenderPipeline build(Key key) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl("pipeline/crumbling/" + key.cacheName()))
                             .withVertexShader(
                                     vertexId(key.instanceType(), key.indirect(), key.debug() != DebugMode.OFF))
                             .withFragmentShader(fragmentId(key.indirect(), key.smoothness(), key.debug()))
                             .withVertexBinding(0, InternalVertex.VERTEX_FORMAT)
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             // No depth write; polygon offset (1.0, 10.0) == vanilla RenderPipelines.CRUMBLING. The 26.2 GL
                             // backend feeds these straight to glPolygonOffset (GlCommandEncoder._polygonOffset, no sign
                             // flip); under reversed-Z a POSITIVE bias raises window depth = pulls the overlay toward the
                             // camera so it wins the GREATER_THAN_OR_EQUAL test against the block instead of z-fighting it.
                             // (The opaque pipelines' -1/-10 is the legacy conventional-Z sign, inverted under reversed-Z.)
                             .withDepthStencilState(
                                     new DepthStencilState(key.depthTest().compareOp, false, 1.0f, 10.0f))
                             .withCull(key.cull())
                             .withBindGroupLayout(bindGroup(key.indirect()))
                             .withColorTargetState(
                                     new ColorTargetState(Optional.of(CRUMBLING_BLEND), GpuFormat.RGBA8_UNORM,
                                             ColorTargetState.WRITE_ALL))
                             .build();
    }

    // Mirrors the opaque pipeline's bind group + the per-stage crack sampler. Instancing carries the instance
    // texel buffer + the light LUT/sections texel buffers in the bind group; indirect raw-binds the object +
    // light SSBOs (the encoder owns no SSBO bindings), so only the samplers + the two UBOs ride the bind group.
    private static BindGroupLayout bindGroup(boolean indirect) {
        BindGroupLayout.Builder b = BindGroupLayout.builder()
                                                   .withSampler("Sampler0")
                                                   .withSampler("Sampler1")
                                                   .withSampler("Sampler2")
                                                   .withSampler("_flw_crumblingTex");
        if (!indirect) {
            b = b.withUniform("_flw_instances", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
                 .withUniform("_flw_lightLut", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                 .withUniform("_flw_lightSections", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
        }
        return b.withUniform("_FlwInstanceDraw", UniformType.UNIFORM_BUFFER)
                .withUniform("_FlwRenderOrigin", UniformType.UNIFORM_BUFFER)
                .build();
    }

    private record FragmentKey(boolean indirect, LightSmoothness smoothness, DebugMode debug) {
    }

    private record Key(InstanceType<?> instanceType, boolean indirect, LightSmoothness smoothness,
                       DebugMode debug, DepthTest depthTest, boolean cull) {
        String cacheName() {
            StringBuilder sb = new StringBuilder();
            sb.append(indirect ? "ind_" : "ins_")
              .append(ResourceUtil.toDebugFileNameNoExtension(instanceType.vertexShader()))
              .append('_')
              .append(smoothness.getSerializedName())
              .append('_')
              .append(depthTest.name());
            if (debug != DebugMode.OFF) {
                sb.append("_debug_")
                  .append(debug.getSerializedName());
            }
            if (cull) {
                sb.append("_cull");
            }
            return sb.toString()
                     .toLowerCase(Locale.ROOT);
        }
    }
}
