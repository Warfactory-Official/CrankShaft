package dev.engine_room.flywheel.iris.compile;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlConst;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import dev.engine_room.flywheel.backend.engine.CrumblingPipelines;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.instancing.InstancingPipeline;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.FramebufferDescription;
import net.irisshaders.iris.backend.GraphicsPipeline;
import net.irisshaders.iris.backend.GraphicsShader;
import net.irisshaders.iris.backend.IrisRenderPass;
import net.irisshaders.iris.backend.PipelineState;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder.TextureBinding;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.PatchedShader;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.WorldShaderTransformer;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.irisshaders.iris.targets.RenderFramebuffer;
import net.irisshaders.iris.targets.WorldRenderTargets.Phase;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Pipeline-owned guest shaders and retirement on Iris's selected backend.
 */
public final class ExperimentalPipelines {
    /**
     * Sampler-heap slots reserved at pack load; further overlay registrations draw unfused.
     */
    public static final int MAX_OVERLAYS = 8;
    private static final VertexFormat VERTICES = VertexFormat.builder(0)
                                                             .addAttribute("_flw_aPosition", GpuFormat.RGB32_FLOAT)
                                                             .addAttribute("_flw_aColor", GpuFormat.RGBA8_UNORM)
                                                             .addAttribute("_flw_aTexCoord", GpuFormat.RG32_FLOAT)
                                                             .addAttribute("_flw_aOverlay", GpuFormat.RG16_SINT)
                                                             .addAttribute("_flw_aLight", GpuFormat.RG16_UINT)
                                                             .addAttribute("_flw_aNormal", 4, GpuFormat.RGB8_SNORM)
                                                             .build();
    private static final VertexFormat EXTRAS = VertexFormat.builder(0)
                                                           .addAttribute("_flw_aIrisEntity", GpuFormat.RG16_SINT)
                                                           .addAttribute("_flw_aMidTexCoord", GpuFormat.RG32_FLOAT)
                                                           .addAttribute("_flw_aTangent", GpuFormat.RGBA8_SNORM)
                                                           .addAttribute("_flw_aMidBlock", GpuFormat.RGBA8_SNORM)
                                                           .addAttribute("_flw_aFaceNormal", GpuFormat.RGBA8_SNORM)
                                                           .build();
    private static final List<VertexFormat> FORMATS = List.of(VERTICES, EXTRAS);
    private static final Material TERRAIN_MATERIAL = SimpleMaterial.builder().build();
    private static final Map<Key, Program> PROGRAMS = new HashMap<>();
    private static IrisRenderingPipeline owner;
    private static boolean overlayCapWarned;

    private ExperimentalPipelines() {
    }

    static List<BlendMode> blends(BlendMode blend, List<BufferBlendInformation> overrides, int[] drawBuffers) {
        List<BlendMode> blends = new ArrayList<>(Collections.nCopies(drawBuffers.length, blend));
        for (var override : overrides) {
            for (int i = 0; i < drawBuffers.length; i++) {
                if (drawBuffers[i] == override.index()) blends.set(i, override.blendMode());
            }
        }
        return blends;
    }

    public static Program get(PackRole role, Material material, InstanceType<?> type, boolean embedded,
                              boolean indirect, Phase phase) {
        return get(role, material, type, embedded, indirect, phase, null);
    }

    public static Program get(PackRole role, Material material, InstanceType<?> type, boolean embedded,
                              boolean indirect, Phase phase, OitPass pass) {
        var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        if (owner != pipeline) {
            release();
            owner = pipeline;
        }
        var key = key(role, material, type, embedded, indirect, phase, null, pass);
        return PROGRAMS.computeIfAbsent(key, _ -> new Program(pipeline, key));
    }

    /**
     * Depth-only redraw of a translucent draw: the colour program's positions, depth written, no colour.
     */
    public static Program depthFill(PackRole role, Material material, InstanceType<?> type, boolean embedded,
                                    boolean indirect) {
        var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        if (owner != pipeline) {
            release();
            owner = pipeline;
        }
        var colour = key(role, material, type, embedded, indirect, Phase.TRANSLUCENT, null, null);
        var key = new Key(colour.program(), colour.transparency(), colour.depthTest(), true, false, colour.cull(),
                colour.offset(), colour.phase(), null);
        return PROGRAMS.computeIfAbsent(key, _ -> new Program(pipeline, key));
    }

    public static Program terrain(boolean cutout, boolean translucent, boolean shadow) {
        return terrain(cutout, translucent, shadow, null);
    }

    public static Program terrain(boolean cutout, boolean translucent, boolean shadow, OitPass pass) {
        var parent = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        if (owner != parent) {
            release();
            owner = parent;
        }
        var phase = shadow ? Phase.SHADOW : translucent ? Phase.TRANSLUCENT : Phase.OPAQUE;
        var role = shadow ? PackRole.SHADOW : translucent ? PackRole.TERRAIN_TRANSLUCENT : PackRole.TERRAIN;
        var overlays = TerrainOverlayDraw.sources();
        if (overlays.size() > MAX_OVERLAYS) {
            if (!overlayCapWarned) {
                overlayCapWarned = true;
                FlwBackend.LOGGER.warn("{} terrain overlays exceed the experimental Iris guest's {}; terrain draws "
                        + "without them", overlays.size(), MAX_OVERLAYS);
            }
            overlays = List.of();
        }
        var key = key(role, TERRAIN_MATERIAL, null, false, false, phase,
                new Terrain(cutout, translucent, TerrainVertexFormat.current(), overlays), pass);
        return PROGRAMS.computeIfAbsent(key, _ -> new Program(parent, key));
    }

    public static void release() {
        PROGRAMS.values().forEach(Program::close);
        PROGRAMS.clear();
        owner = null;
    }

    public static void release(IrisRenderingPipeline pipeline) {
        if (owner == pipeline) release();
    }

    private static Key key(PackRole role, Material material, InstanceType<?> type, boolean embedded,
                           boolean indirect, Phase phase, Terrain terrain, OitPass pass) {
        var program = ProgramKey.of(role.forMaterial(material), indirect, indirect ? null : type, material, embedded,
                role == PackRole.DAMAGED,
                indirect ? InstanceTypeIds.generation() : 0);
        if (pass != null) program = program.withOit(pass);
        return new Key(program, material.transparency(), material.depthTest(),
                GuestProgramResolver.depthWrite(owner, program,
                        material.writeMask().depth()), material.writeMask().color(),
                !role.shadow && material.backfaceCulling(),
                !role.shadow && material.polygonOffset(), phase, terrain);
    }

    private record Key(ProgramKey program, Transparency transparency,
                       DepthTest depthTest, boolean depthWrite,
                       boolean colorWrite,
                       boolean cull, boolean offset, Phase phase, Terrain terrain) {
    }

    private record Terrain(boolean cutout, boolean translucent, VertexFormat format, List<Identifier> overlays) {
    }

    public static final class Program {
        private final IrisRenderingPipeline parent;
        private final GraphicsShader shader;
        private final GraphicsPipeline pipeline;
        private final BufferUniformHolder uniforms;
        private final RenderFramebuffer framebuffer;
        private final BlendMode blend;
        private final AlphaTest alpha;
        private final GpuBuffer zeroVertices;
        private final ExperimentalOit oit;
        private final OitPass oitPass;
        private final boolean flatten;
        private final boolean bindless;
        private final Map<String, List<GpuBufferSlice>> buffers = new HashMap<>();
        private Map<String, Long> texels = Map.of();
        private GpuTextureView albedo;
        private GpuSampler sampler;
        private GpuTextureView crumbling;

        private Program(IrisRenderingPipeline parent, Key key) {
            this.parent = parent;
            var access = parent;
            var terrain = key.terrain();
            oitPass = key.program().oit();
            oit = oitPass == null ? null : Objects.requireNonNull(
                    ExperimentalOit.get(parent, key.program().role().shadow));
            List<VertexFormat> formats = terrain == null ? FORMATS : List.of(terrain.format());
            ContractProgram contract = null;
            ProgramSource source;
            ProgramId nativeId = terrain == null ? key.program()
                                                      .role().programId : terrain.translucent() ? ProgramId.Water
                    : key.program().role().shadow ? (terrain.cutout() ? ProgramId.ShadowCutout : ProgramId.ShadowSolid)
                    : terrain.cutout() ? ProgramId.TerrainCutout : ProgramId.TerrainSolid;
            var programKey = key.program();
            if (terrain == null) {
                var resolved = GuestProgramResolver.resolve(parent, programKey);
                programKey = resolved.program();
                contract = resolved.contract();
                source = resolved.source();
            } else {
                programKey = programKey.forNative();
                var nativeSource = ((IrisRenderingPipelineAccessor) access).flywheel$resolver().resolve(nativeId)
                                                                           .orElseThrow();
                var forward = ForwardOit.get(parent);
                source = oit != null && !key.program().role().shadow && forward != null ? forward.source(
                        nativeSource) : nativeSource;
            }
            boolean authored = contract != null;
            var packState = terrain == null ? GuestProgramResolver.state(parent, programKey, contract, source) : null;
            AlphaTest fallbackAlpha = terrain == null ? programKey.alphaTest()
                    // Iris (SHADOW_)SODIUM_TERRAIN_* alpha tests.
                    : terrain.translucent() ? (key.program().role().shadow ? AlphaTests.ONE_TENTH_ALPHA
                    : AlphaTests.NON_ZERO_ALPHA) : terrain.cutout() ? AlphaTests.HALF_ALPHA : AlphaTests.OFF;
            alpha = terrain != null && oit != null && !key.program().role().shadow
                    && oit.spec(oitPass).oit().forward() != null ? AlphaTests.OFF
                    : terrain == null ? packState.alpha() : source.getDirectives().getAlphaTestOverride()
                                                                  .orElse(fallbackAlpha);
            GuestShaders.Stages stages;
            if (terrain == null) {
                stages = GuestShaders.build(parent, source, alpha, programKey, authored,
                        oit == null ? null : oit.spec(oitPass), false, false,
                        GuestShaders.capture(source, programKey, key.depthWrite(),
                                key.depthTest().compareOp != CompareOp.ALWAYS_PASS,
                                "experimental/" + programKey.cacheName()));
            } else {
                var terrainStages = oit == null ? GuestTerrainShaders.build(parent, source, alpha,
                        key.program().role().shadow, terrain.overlays(), terrain.translucent())
                        : GuestTerrainShaders.buildOit(parent, source, alpha, source.getDirectives().getDrawBuffers(),
                        oit.spec(oitPass), terrain.overlays());
                stages = new GuestShaders.Stages(terrainStages.vertex(), terrainStages.geometry(), null, null,
                        terrainStages.fragment());
            }
            var sources = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
            sources.put(PatchShaderType.VERTEX, stages.vertex());
            sources.put(PatchShaderType.FRAGMENT, stages.fragment());
            if (stages.geometry() != null) sources.put(PatchShaderType.GEOMETRY, stages.geometry());
            if (stages.tessControl() != null) sources.put(PatchShaderType.TESS_CONTROL, stages.tessControl());
            if (stages.tessEval() != null) sources.put(PatchShaderType.TESS_EVAL, stages.tessEval());
            sources.replaceAll((stage, text) -> NativeShaderPreprocessor.preprocessKind(text,
                    "flywheel:iris/guest/" + stage, switch (stage) {
                        case VERTEX -> Shaderc.shaderc_vertex_shader;
                        case FRAGMENT -> Shaderc.shaderc_fragment_shader;
                        case GEOMETRY -> Shaderc.shaderc_geometry_shader;
                        case TESS_CONTROL -> Shaderc.shaderc_tess_control_shader;
                        case TESS_EVAL -> Shaderc.shaderc_tess_evaluation_shader;
                        case COMPUTE -> Shaderc.shaderc_compute_shader;
                    }, Map.of()));

            if (packState != null && packState.grayscale())
                sources.replaceAll((_, text) -> GuestGlyphAlbedo.patch(text));
            var converted = terrain == null && programKey.indirect() && !programKey.crumbling()
                    && ExperimentalBindless.available() ? ExperimentalBindless.convert(sources) : null;

            String name = "flywheel_" + programKey.cacheName() + (terrain == null ? "" : "_terrain_" + terrain.cutout() + "_" + terrain.translucent());
            int[] drawBuffers = source.getDirectives().getDrawBuffers();
            if (oit != null) drawBuffers = IntStream.range(0, oit.colors(oitPass).size()).toArray();
            var attachmentBuilder = new ColorAttachmentPlan.Builder();
            attachmentBuilder.program(name, drawBuffers, parent.getWorldTargets().getFlippedBuffers(key.phase()));
            var attachmentPlan = attachmentBuilder.buildGroups(
                                                          RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments())
                                                  .stream().filter(plan -> plan.programNames().contains(name))
                                                  .findFirst().orElseThrow();
            var outputs = attachmentPlan.program(name);
            var prepared = prepare(parent, key, converted == null ? sources : converted, name, outputs);
            if (converted != null && VkContext.isVulkanHost()
                    && !ExperimentalBindless.admits(prepared.shader(), prepared.uniforms().frameLayout(),
                    outputs.attachmentCount())) {
                // Reject only the table variant, before GPU compilation; ordinary texture runs keep the same material.
                converted = null;
                prepared = prepare(parent, key, sources, name, outputs);
            }
            bindless = converted != null;
            uniforms = prepared.uniforms();
            var resources = prepared.resources();
            var patched = prepared.shader();
            if (bindless && VkContext.isVulkanHost()) patched = ExperimentalBindless.declareTable(patched);
            flatten = key.program().role() == PackRole.PARTICLES_FLATTEN;
            framebuffer = oit != null || flatten ? null : key.program()
                                                  .role().shadow ? ((IrisRenderingPipelineAccessor) access).flywheel$shadowTargets()
                                                                                                           .get()
                                                                                                           .createMergedFramebuffer(
                                                                                                                   attachmentPlan)
                    : ((IrisRenderingPipelineAccessor) access).flywheel$renderTargets()
                                                              .createMergedFramebuffer(attachmentPlan, true);
            var colors = flatten ? ExperimentalFlatten.colors(parent)
                    : oit == null ? framebuffer.getColorViews() : oit.colors(oitPass);
            var depth = flatten ? ExperimentalFlatten.depth(parent)
                    : oit == null ? framebuffer.getDepthView() : oit.depth();
            var stateBuilder = (key.program().crumbling() ? CrumblingPipelines.stateBuilder(key.depthTest(), key.cull(),
                    false)
                    : InstancingPipeline.stateBuilder(key.transparency(), key.depthTest(),
                    oit == null && key.depthWrite(), key.colorWrite(), key.cull(),
                    key.offset(), key.program().embedded())).withLocation(ResourceUtil.rl(name))
                                                            .withVertexShader(ResourceUtil.rl("iris/guest"))
                                                            .withFragmentShader(ResourceUtil.rl("iris/guest"))
                                                            .withVertexBinding(0, formats.getFirst());
            if (terrain == null) stateBuilder.withVertexBinding(1, EXTRAS);
            if (oit != null) stateBuilder.withColorTargetState(new ColorTargetState(Optional.of(
                    oitPass == OitPass.DEPTH_RANGE ? BlendFunction.MAX : BlendFunction.ADDITIVE),
                    GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
            RenderPipeline state = stateBuilder.build();
            BlendModeOverride blendOverride;
            List<BufferBlendInformation> bufferBlends;
            if (oit != null) {
                blendOverride = null;
                bufferBlends = List.of();
            } else if (terrain == null) {
                blendOverride = packState.blend();
                bufferBlends = flatten ? GuestParticleFlatten.BUFFER_BLENDS : packState.bufferBlends();
            } else {
                blendOverride = source.getDirectives().getBlendModeOverride().orElse(nativeId.getBlendModeOverride());
                bufferBlends = source.getDirectives().getBufferBlendOverrides();
            }
            BlendMode inheritedBlend = null;
            if (!state.getColorTargetStates().isEmpty() && state.getColorTargetStates().getFirst().blendFunction()
                                                                .isPresent()) {
                var function = state.getColorTargetStates().getFirst().blendFunction().get();
                inheritedBlend = new BlendMode(GlConst.toGl(function.color().sourceFactor()),
                        GlConst.toGl(function.color().destFactor()),
                        GlConst.toGl(function.alpha().sourceFactor()), GlConst.toGl(function.alpha().destFactor()));
            }
            blend = blendOverride == null ? inheritedBlend : blendOverride.blendMode();
            List<BlendMode> blends = blends(blend, bufferBlends, drawBuffers);
            GpuBuffer fallback = null;
            if (bindless) ExperimentalBindless.prepareTable();
            shader = Iris.getRenderBackend().createGraphicsShader(name, patched, resources, uniforms);
            try {
                uniforms.resolveLayout(shader.getSource().layout());
                var attributes = WorldShaderTransformer.attributes(
                        shader.getSource().sources().get(PatchShaderType.VERTEX), formats);
                int fallbackBinding = WorldShaderTransformer.fallbackVertexBinding(formats);
                fallback = attributes.stream().anyMatch(attribute -> attribute.binding() == fallbackBinding)
                        ? RenderSystem.getDevice().createBuffer(() -> "flywheel guest zero vertex attributes",
                        GpuBuffer.USAGE_VERTEX, ByteBuffer.allocateDirect(32)) : null;
                pipeline = shader.createPipeline(new PipelineState(new FramebufferDescription(
                        colors.stream().map(v -> v.texture().getFormat()).toList(),
                        depth == null ? null : depth.texture().getFormat()),
                        outputs.remapBlends(blends), attributes, 0, state, key.program().role().shadow, fallback,
                        formats));
                zeroVertices = fallback;
            } catch (RuntimeException failure) {
                if (fallback != null) fallback.close();
                shader.close();
                throw failure;
            }
        }

        private static void addOverlaySamplers(ProgramResources resources, int count) {
            var samplers = RenderSystem.getSamplerCache();
            resources.addDrawTextureSampler(new TextureBinding("flywheel/terrain_overlay/lightmap",
                    () -> Minecraft.getInstance().gameRenderer.lightmap(), () -> GlSampler.LINEAR,
                    () -> samplers.getClampToEdge(FilterMode.LINEAR)), false, "TerrainOverlayLightmap");
            for (int index = 0; index < count; index++) {
                int overlay = index;
                resources.addDrawTextureSampler(new TextureBinding("flywheel/terrain_overlay/texture/" + index,
                                () -> TerrainOverlayDraw.overlays().get(overlay).texture(), () -> GlSampler.NEAREST,
                                () -> TerrainOverlayDraw.overlays().get(overlay).sampler()), false,
                        "TerrainOverlayTexture" + index);
                resources.addDrawTextureSampler(new TextureBinding("flywheel/terrain_overlay/metadata/" + index,
                        () -> TerrainOverlayDraw.overlays().get(overlay).metadata(), () -> GlSampler.NEAREST,
                        () -> samplers.getClampToEdge(FilterMode.NEAREST)), false, "TerrainOverlayMetadata" + index);
            }
        }

        private PreparedShader prepare(IrisRenderingPipeline parent, Key key, Map<PatchShaderType, String> sources,
                                       String name, ColorAttachmentPlan.Program outputs) {
            var terrain = key.terrain();
            var preparedSources = WorldShaderTransformer.patchMatrices(sources, false);
            var layout = VulkanShaderPatcher.discover(preparedSources).layout();
            var uniforms = new BufferUniformHolder(layout);
            uniforms.useFrameUniforms(parent.getFrameUniforms());
            CommonUniforms.addDynamicUniforms(uniforms, FogMode.PER_VERTEX);
            if (terrain != null) {
                uniforms.uniform3i(UniformUpdateFrequency.PER_FRAME, "_flw_sodiumCameraInt",
                        () -> new Vector3i(GuestTerrainGate.cameraIntX, GuestTerrainGate.cameraIntY,
                                GuestTerrainGate.cameraIntZ));
                uniforms.uniform3f(UniformUpdateFrequency.PER_FRAME, "_flw_sodiumCameraFrac",
                        () -> new Vector3f(GuestTerrainGate.cameraFracX, GuestTerrainGate.cameraFracY,
                                GuestTerrainGate.cameraFracZ));
            }
            var resources = new ProgramResources(parent.getPackSamplers(), layout);
            resources.flywheel$externalStorage(
                    Set.of("InstanceBuffer", "_FlwVisibleInstances",
                            "_flw_RegionInputBuf", "_flw_RegionTimeBuf", "TargetBuffer", "DrawBuffer", "MatrixBuffer",
                            "LightLut", "LightSections", "_FlwTextureHandles", "_FlwGuestTextureInfo"));
            resources.addStorageBuffers(parent.getStorageBuffers());
            resources.setUniformBuffers(() -> buffers);
            resources.setExternalBindings(unit -> switch (unit) {
                case 0 -> new TextureBinding("world/texture/0", () -> albedo, () -> GlSampler.NEAREST, () -> sampler);
                case 1 -> new TextureBinding("world/texture/1", () -> Minecraft.getInstance()
                        .gameRenderer.overlayTexture().getTextureView(), () -> GlSampler.NEAREST);
                case 2 -> new TextureBinding("world/texture/2", () -> Minecraft.getInstance()
                        .gameRenderer.lightmap(), () -> GlSampler.LINEAR);
                default -> throw new IllegalArgumentException("Unknown guest sampler " + unit);
            });
            resources.addDrawTextureSampler(new TextureBinding("flywheel/crumbling", () -> crumbling,
                    () -> GlSampler.NEAREST), false, "_flw_crumblingTex");
            if (key.program().role() == PackRole.PARTICLES_HANDOFF) {
                resources.addDrawTextureSampler(new TextureBinding(ExperimentalFlatten.SAMPLER,
                        ExperimentalFlatten::target, () -> GlSampler.NEAREST), false, GuestParticleFlatten.TARGET);
                resources.addDrawTextureSampler(new TextureBinding(ExperimentalFlatten.IDS_SAMPLER,
                        ExperimentalFlatten::ids, () -> GlSampler.NEAREST), false, GuestParticleFlatten.TARGET_IDS);
            }
            if (terrain != null && !terrain.overlays().isEmpty()) {
                addOverlaySamplers(resources, terrain.overlays().size());
            }
            for (var descriptor : layout.descriptors()) {
                if (descriptor.kind() == ShaderResourceLayout.Kind.SAMPLER && descriptor.type().endsWith("BUFFER")) {
                    var samplerName = descriptor.name();
                    resources.addTexelSampler(samplerName, "flywheel/" + samplerName, () -> texels.get(samplerName));
                }
            }
            boolean shadow = key.program().role().shadow;
            var flipped = ImmutableSet.copyOf(shadow ? parent.getFlippedBeforeShadow()
                    : parent.getWorldTargets().getFlippedBuffers(key.phase()));
            parent.addGbufferOrShadowSamplers(resources, resources, () -> flipped, shadow, true, true, true,
                    shadow ? Phase.SHADOW : key.phase());
            if (oit != null) {
                for (var descriptor : layout.descriptors())
                    if (descriptor.kind() == ShaderResourceLayout.Kind.SAMPLER
                            && (descriptor.name().equals("_flw_depthRange") || descriptor.name()
                                                                                         .equals("_flw_blueNoise")
                            || descriptor.name().startsWith("_flw_coefficients"))) {
                        String samplerName = descriptor.name();
                        resources.addDrawTextureSampler(new TextureBinding("flywheel/oit/" + samplerName, () ->
                                oitPass == OitPass.DEPTH_RANGE && samplerName.equals("_flw_depthRange")
                                        ? NoiseTextures.BLUE_NOISE.getTextureView() : oit.texture(samplerName),
                                () -> GlSampler.NEAREST), false, samplerName);
                    }
                var forward = oit.spec(oitPass).oit().forward();
                if (forward != null && oitPass == OitPass.EVALUATE) {
                    resources.addTextureImage(() -> oit.texture("_flw_forwardSurfaceClaim"),
                            InternalTextureFormat.R32UI, "_flw_forwardSurfaceClaim");
                    for (int slot = 0; slot < forward.waterBuffers().length; slot++) {
                        String imageName = "_flw_forwardWater" + slot;
                        resources.addTextureImage(() -> oit.texture(imageName), InternalTextureFormat.RGBA32UI,
                                imageName);
                    }
                }
            }
            resources.finish();
            var patched = TransformPatcher.patchProgram(name, preparedSources, resources, uniforms, outputs);
            return new PreparedShader(resources, uniforms, patched);
        }

        public IrisRenderPass open() {
            var colors = flatten ? ExperimentalFlatten.colors(parent)
                    : oit == null ? framebuffer.getColorViews() : oit.colors(oitPass);
            var depth = flatten ? ExperimentalFlatten.depth(parent)
                    : oit == null ? framebuffer.getDepthView() : oit.depth();
            var size = colors.isEmpty() ? depth.texture() : colors.getFirst().texture();
            return Iris.getRenderBackend()
                       .createRenderPass("flywheel:iris/guest", pipeline.framebuffer(), colors, depth,
                               new Vector2i(size.getWidth(0), size.getHeight(0)));
        }

        public void bind(IrisRenderPass pass, Map<String, List<GpuBufferSlice>> buffers, Map<String, Long> texels,
                         GpuTextureView albedo, GpuSampler sampler) {
            for (var descriptor : shader.getSource().layout().descriptors()) {
                if (descriptor.kind() != ShaderResourceLayout.Kind.UNIFORM_BUFFER
                        && descriptor.kind() != ShaderResourceLayout.Kind.STORAGE_BUFFER) continue;
                var slices = buffers.get(descriptor.name());
                if (slices == null) this.buffers.remove(descriptor.name());
                else this.buffers.put(descriptor.name(), slices);
            }
            this.texels = texels;
            this.albedo = albedo;
            this.sampler = sampler;
            // Iris derives atlasSize and the PBR maps from frontend Sampler0 binds, which Iris render passes bypass.
            parent.onSetAlbedoTex(albedo);
            CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alpha.reference());
            CapturedRenderingState.INSTANCE.setBlendMode(blend);
            var mc = Minecraft.getInstance();
            uniforms.update(mc.level.getGameTime(),
                    SystemTimeUniforms.COUNTER.getAsInt());
            // Iris resolves draw bindings and prepares storage-image access when binding the pipeline.
            pass.bindPipeline(pipeline);
            // Resource uploads can resume a packed pass at attachment size; restore its native viewport after binding.
            if (oit != null) oit.bindViewport(pass);
        }

        /**
         * Albedo from the draw command's slot: batches may mix textures.
         */
        public boolean bindless() {
            return bindless;
        }

        public void crumblingTexture(GpuTextureView texture) {
            crumbling = texture;
        }

        private void close() {
            if (zeroVertices != null) Iris.getRenderBackend().retire(zeroVertices::close);
            Iris.getRenderBackend().retire(pipeline::close);
            Iris.getRenderBackend().retire(shader::close);
        }

        private record PreparedShader(ProgramResources resources, BufferUniformHolder uniforms,
                                      PatchedShader shader) {
        }
    }
}
