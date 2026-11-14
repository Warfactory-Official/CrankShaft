package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
#if MC < 26.3
import com.mojang.blaze3d.opengl.GlRenderPass.TextureViewAndSampler;
import dev.engine_room.flywheel.iris.compile.patches.GuestLodOcclusion;
#endif
import dev.engine_room.flywheel.iris.engine.GuestOitComposite;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
#if MC >= 26.3
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
#endif
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.BackendRecovery;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.GlAsyncCompiler;
import dev.engine_room.flywheel.backend.compile.GlCompilationBatch;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import net.minecraft.client.renderer.ShaderDefines;
#endif
import dev.engine_room.flywheel.backend.compile.PipelineVersions;
import dev.engine_room.flywheel.backend.compile.ProgramGeneration;
import dev.engine_room.flywheel.backend.compile.ProgramVersions;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.backend.compile.ShaderCompilationMode;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.compile.TerrainOverlayPipelines;
import dev.engine_room.flywheel.backend.compile.component.InstanceAssemblerComponent;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.CrumblingPipelines;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedInstancer;
import dev.engine_room.flywheel.backend.engine.instancing.InstancingPipeline;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.iris.IrisBackends;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.iris.engine.GuestVertexExtras;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.compile.patches.OrderedBillboardCapture;
import dev.engine_room.flywheel.iris.compile.patches.SortedBillboardBlend;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.*;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * Render-thread only; guest caches scoped to Iris pipeline ownership.
 */
public final class GuestPipelines {
    private static final Identifier GUEST_SHADER = ResourceUtil.rl("iris/guest");

    private static final Map<PipelineKey, RenderPipeline> PIPELINES = new HashMap<>();
    private static final Map<PipelineKey, RenderPipeline> LIGHT_PIPELINES = new HashMap<>();
    private static final Set<RenderPipeline> SNAPSHOT_PIPELINES = new ReferenceOpenHashSet<>();
    private static final Map<RenderPipeline, ProgramKey> PROGRAM_KEYS = new IdentityHashMap<>();
    private static final Map<RenderPipeline, VersionKey> VERSION_KEYS = new IdentityHashMap<>();
    private static final Map<VersionKey, ProgramVersions<RenderPipeline>> VERSIONS = new HashMap<>();
    private static final Map<VersionKey, Set<RenderPipeline>> VERSION_DESCRIPTORS = new HashMap<>();
    private static final Set<RenderPipeline> RETIRED = new ReferenceOpenHashSet<>();
    private static final Set<RenderPipeline> TRACKED = new ReferenceOpenHashSet<>();
    private static final ProgramVersions.Tracker<RenderPipeline> VERSION_TRACKER = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(RenderPipeline pipeline) {
            return ready(pipeline) ? ProgramVersions.State.PUBLISHED : ProgramVersions.State.PENDING;
        }

        @Override
        public void release(RenderPipeline pipeline) {
            TRACKED.remove(pipeline);
            RETIRED.add(pipeline);
        }
    };

    private static final Map<RenderPipeline, Boolean> COMPOSITE_SHADOW = new IdentityHashMap<>();
    private static final RenderPipeline[] COMPOSITES = new RenderPipeline[2];
    private static final Map<RenderPipeline, Boolean> DEPTH_SHADOW = new IdentityHashMap<>();
    private static final RenderPipeline[] DEPTHS = new RenderPipeline[3];
    private static RenderPipeline FORWARD_WATER;
    private static GpuFormat FORWARD_WATER_FORMAT;
    private static final BlendFunction MAX_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX,
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    private static final BlendFunction ADD_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE);
    private static final BlendFunction COMPOSITE_BLEND = new BlendFunction(BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final BlendFunction SORTED_BLEND = new BlendFunction(BlendFactor.ONE,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final Map<LinkKey, GuestProgram> PROGRAMS = new HashMap<>();
    private static final Map<RenderPipeline, ResolvedProgram> RESOLVED = new IdentityHashMap<>();
    private static final Map<LinkKey, Integer> PROGRAM_REFERENCES = new HashMap<>();
    private static final Map<LinkKey, GlAsyncCompiler.Pending> LINKING = new HashMap<>();
    private static final Map<ProgramKey, ProgramKey> BINDLESS = new HashMap<>();
    private static final Map<ProgramKey, ProgramKey> VELOCITY = new HashMap<>();
    private static final Pattern VELOCITY_INPUT = Pattern.compile("\\b" + GuestShaders.VELOCITY + "\\b");
    private static final Map<RenderPipeline, CompiledRenderPipeline> COMPILED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, CompiledRenderPipeline> MESH_COMPILED = new IdentityHashMap<>();
    private static final GuestOitTargets[] OIT_TARGETS = new GuestOitTargets[2];
    private static final GuestFlattenTarget FLATTEN_TARGET = new GuestFlattenTarget();
    private static final GuestCaptureDepth CAPTURE_DEPTH = new GuestCaptureDepth();
    private static GuestProgram[] LIGHT_DEMAND_PROGRAMS;
    private static GuestProgram SORTED_BLEND_LATCH;
    private static int sortedBlendPhase;
    private static int sortedBlendRangeCount;
    #if MC < 26.3
    private static final HashMap<String, TextureViewAndSampler> COMPUTE_TEXTURES = new HashMap<>();
    #endif
    private static @Nullable IrisRenderingPipeline bindlessOwner;
    private static @Nullable IrisRenderingPipeline velocityOwner;
    private static boolean velocityDemanded;
    private static @Nullable IrisRenderingPipeline owner;
    private static final Map<LinkKey, GuestProgram> STATIC_SHADOW_PROGRAMS = new HashMap<>();
    private static final Map<LinkKey, GlAsyncCompiler.Pending> STATIC_SHADOW_LINKING = new HashMap<>();
    private static @Nullable IrisRenderingPipeline warmed;
    private static @Nullable IrisRenderingPipeline glintOwner;
    private static boolean nativeGlint;
    private static int decisionGeneration;

    private GuestPipelines() {
    }

    /**
     * Releases superseded descriptors after the preceding frame has finished using them.
     */
    public static void poll() {
        int generation = InstanceTypeIds.snapshot().types().size();
        if (generation != decisionGeneration) {
            BINDLESS.keySet().removeIf(key -> key.typeGen() > 0 && key.typeGen() < generation);
            VELOCITY.keySet().removeIf(key -> key.typeGen() > 0 && key.typeGen() < generation);
            decisionGeneration = generation;
        }
        for (RenderPipeline pipeline : RETIRED) {
            VersionKey version = VERSION_KEYS.remove(pipeline);
            ProgramKey key = PROGRAM_KEYS.remove(pipeline);
            PipelineKey pipelineKey = version.key().withTypeGen(key.typeGen());
            PIPELINES.remove(pipelineKey, pipeline);
            LIGHT_PIPELINES.remove(pipelineKey, pipeline);
            SNAPSHOT_PIPELINES.remove(pipeline);
            Set<RenderPipeline> descriptors = VERSION_DESCRIPTORS.get(version);
            descriptors.remove(pipeline);
            if (descriptors.isEmpty()) VERSION_DESCRIPTORS.remove(version);
            PipelineVersions.untrack(pipeline);
            CompiledRenderPipeline compiled = COMPILED.remove(pipeline);
            CompiledRenderPipeline mesh = MESH_COMPILED.remove(pipeline);
            #if MC >= 26.3
            if (compiled != null || mesh != null) RenderPipelineCompiler.unregister(pipeline);
            if (compiled != null) RenderPipelineCompiler.releaseGuestPipeline((FrontendRenderPipeline) compiled);
            if (mesh != null) RenderPipelineCompiler.releaseGuestPipeline((FrontendRenderPipeline) mesh);
            #endif
            ResolvedProgram resolved = RESOLVED.remove(pipeline);
            if (resolved == null) continue;
            LinkKey link = resolved.key();
            int references = PROGRAM_REFERENCES.get(link) - 1;
            if (references != 0) {
                PROGRAM_REFERENCES.put(link, references);
                continue;
            }
            PROGRAM_REFERENCES.remove(link);
            GlAsyncCompiler.Pending pending = LINKING.remove(link);
            if (pending != null) pending.cancel();
            GuestProgram program = PROGRAMS.remove(link);
            if (program != null) program.close();
        }
        RETIRED.clear();
    }

    /**
     * Draw time: null until the pack program is linked, linked in the background (published at the frame's
     * {@link RenderPipelineCompiler#poll}); likewise every getter below.
     */
    public static @Nullable RenderPipeline instancing(PackRole role, Material material, InstanceType<?> type,
                                                      boolean embedded) {
        return published(instancingDescriptor(role, material, type, embedded));
    }

    /**
     * Type-erased; {@code embedded} selects fragment variant. The newest uber generation linked for the pack, null
     * before the first; likewise every indirect getter below.
     */
    public static @Nullable RenderPipeline indirect(PackRole role, Material material, boolean embedded) {
        return indirect(role, material, null, embedded);
    }

    public static @Nullable RenderPipeline indirect(PackRole role, Material material, @Nullable InstanceType<?> type,
                                                    boolean embedded) {
        return newest(indirectDescriptor(role, material, type, embedded));
    }

    public static @Nullable RenderPipeline indirectSortedBillboard(PackRole role, Material material, boolean embedded,
                                                                   SortedBlendPass pass) {
        return newest(indirectSortedBillboardDescriptor(role, material, embedded, pass));
    }

    public static @Nullable RenderPipeline instancingLightCapture(PackRole role, Material material,
                                                                  InstanceType<?> type, boolean embedded) {
        return published(instancingLightCaptureDescriptor(role, material, type, embedded));
    }

    public static @Nullable RenderPipeline indirectLightCapture(PackRole role, Material material, boolean embedded) {
        return newest(indirectLightCaptureDescriptor(role, material, embedded));
    }

    public static @Nullable RenderPipeline instancingDepthFill(PackRole role, Material material, InstanceType<?> type,
                                                               boolean embedded) {
        return published(instancingDepthFillDescriptor(role, material, type, embedded));
    }

    public static @Nullable RenderPipeline indirectDepthFill(PackRole role, Material material, boolean embedded) {
        return newest(indirectDepthFillDescriptor(role, material, embedded));
    }

    public static @Nullable RenderPipeline instancingOit(PackRole role, OitPass pass, Material material,
                                                         InstanceType<?> type, boolean embedded) {
        return published(instancingOitDescriptor(role, pass, material, type, embedded));
    }

    public static @Nullable RenderPipeline indirectOit(PackRole role, OitPass pass, Material material,
                                                       boolean embedded) {
        return newest(indirectOitDescriptor(role, pass, material, embedded));
    }

    public static @Nullable RenderPipeline crumbling(Material crumblingMaterial, InstanceType<?> type,
                                                     boolean indirect) {
        return published(crumblingDescriptor(crumblingMaterial, type, indirect));
    }

    /**
     * The main translucent program fed retained far-layer records ({@link FarLayerMainGuest}), as forward OIT producer
     * {@code pass} or, when null, in one pass.
     */
    public static @Nullable RenderPipeline farLayer(@Nullable OitPass pass) {
        return published(farLayerDescriptor(pass));
    }

    /**
     * Whether every pass the main view draws {@link #farLayer} through is published.
     */
    static boolean farLayerReady(IrisRenderingPipeline pipeline) {
        if (deferredOitActive(pipeline) || !oitActive(pipeline, false)) return farLayer(null) != null;
        boolean coefficients = Objects.requireNonNull(oitProperties(pipeline, false)).oit(false).ranks().length > 0;
        return farLayer(OitPass.DEPTH_RANGE) != null && (!coefficients || farLayer(OitPass.COEFFICIENTS) != null)
                && farLayer(OitPass.EVALUATE) != null;
    }

    /**
     * Draw buffers of the program {@link #farLayer} shades through.
     */
    static int[] farLayerDrawBuffers(IrisRenderingPipeline pipeline) {
        return drawBuffers(GuestProgramResolver.resolve(pipeline, farLayerKey(null)).source(), false);
    }

    private static @Nullable RenderPipeline published(RenderPipeline pipeline) {
        return ready(pipeline) ? pipeline : null;
    }

    private static @Nullable RenderPipeline newest(RenderPipeline current) {
        RETIRED.remove(current);
        TRACKED.add(current);
        VersionKey key = VERSION_KEYS.get(current);
        ProgramVersions<RenderPipeline> versions = VERSIONS.computeIfAbsent(key,
                _ -> new ProgramVersions<>(VERSION_TRACKER));
        ProgramGeneration previous = versions.generation();
        RenderPipeline selected = versions.select(new ProgramGeneration(PROGRAM_KEYS.get(current).typeGen(), 0, 0),
                current, pipeline -> pipeline);
        if (selected != null && !previous.equals(versions.generation())) {
            int generation = PROGRAM_KEYS.get(selected).typeGen();
            for (RenderPipeline descriptor : VERSION_DESCRIPTORS.get(key)) {
                if (PROGRAM_KEYS.get(descriptor).typeGen() < generation) RETIRED.add(descriptor);
            }
        }
        return selected;
    }

    /**
     * Whether the material guest pipeline is compiled for the current pack; otherwise links its program in the
     * background, or now without parallel compilation.
     */
    private static boolean ready(RenderPipeline pipeline) {
        IrisRenderingPipeline current = currentPipeline();
        track(current);
        if (COMPILED.containsKey(pipeline)) return true;
        if (!GlAsyncCompiler.available()) {
            compiled(pipeline, current);
            return true;
        }
        ResolvedProgram resolved = resolved(current, pipeline, PROGRAM_KEYS.get(pipeline));
        GuestProgram program = PROGRAMS.get(resolved.key());
        if (program != null) {
            COMPILED.put(pipeline, registerCompiled(pipeline, program));
            return true;
        }
        if (!LINKING.containsKey(resolved.key())) {
            LINKING.put(resolved.key(), linkInBackground(current, pipeline, resolved));
        }
        return false;
    }

    private static GlAsyncCompiler.Pending linkInBackground(IrisRenderingPipeline pipeline,
                                                            RenderPipeline renderPipeline,
                                                            ResolvedProgram resolved) {
        LinkKey key = resolved.key();
        PreparedProgram prepared = prepareLink(pipeline, renderPipeline, key, resolved.source());
        DriverStages stages = driverStages(prepared.label(), prepared.stages());
        String[] labels = new String[stages.types().length];
        Arrays.fill(labels, prepared.label());
        return GlAsyncCompiler.submit(prepared.label(), stages.types(), stages.sources(), labels,
                GuestShaders.ATTRIBUTES, null, null, handle -> {
                    LINKING.remove(key);
                    PROGRAMS.put(key, publishDirect(handle, prepared.publish()));
                }, () -> LINKING.remove(key));
    }

    private static RenderPipeline instancingDescriptor(PackRole role, Material material, InstanceType<?> type,
                                                       boolean embedded) {
        track(currentPipeline());
        PackRole routed = role.forMaterial(material);
        ProgramKey program = velocity(ProgramKey.of(routed, false, type, material, embedded, false, 0));
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(routed, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), embedded), k));
    }

    private static RenderPipeline indirectDescriptor(PackRole role, Material material, @Nullable InstanceType<?> type,
                                                     boolean embedded) {
        track(currentPipeline());
        PackRole routed = role.forMaterial(material);
        ProgramKey program = velocity(bindless(ProgramKey.of(routed, true, type, material, embedded, false,
                InstanceTypeIds.snapshot().types().size())));
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(routed, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), k.program().bindless()), k));
    }

    private static RenderPipeline indirectSortedBillboardDescriptor(PackRole role, Material material,
                                                                    boolean embedded, SortedBlendPass pass) {
        track(currentPipeline());
        PackRole routed = role.forMaterial(material);
        ProgramKey program = velocity(bindless(ProgramKey.of(routed, true, InstanceTypes.BILLBOARD, material,
                embedded, false, InstanceTypeIds.snapshot().types().size()).withSortedBlendPass(pass)));
        PipelineKey key = new PipelineKey(program, material.transparency(), material.depthTest(), false, true,
                material.backfaceCulling(), material.polygonOffset());
        return PIPELINES.computeIfAbsent(key, k -> {
            var builder = IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), false, true, k.cull(),
                    k.polygonOffset(), k.program().bindless());
            if (pass == SortedBlendPass.FAST) {
                builder.withColorTargetState(new ColorTargetState(Optional.of(SORTED_BLEND), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL));
            }
            return register(routed, builder, k, true);
        });
    }

    private static ProgramKey farLayerKey(@Nullable OitPass pass) {
        var key = ProgramKey.of(PackRole.TRANSLUCENT, true, null, FarLayerMainGuest.MATERIAL, false, false, 0)
                            .withFarLayer();
        return pass == null ? key : key.withOit(pass);
    }

    private static RenderPipeline farLayerDescriptor(@Nullable OitPass pass) {
        track(currentPipeline());
        return PIPELINES.computeIfAbsent(materialKey(farLayerKey(pass), FarLayerMainGuest.MATERIAL),
                k -> register(PackRole.TRANSLUCENT, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(),
                        k.depthWrite(), k.colorWrite(), k.cull(), k.polygonOffset(), false), k));
    }

    private static RenderPipeline instancingLightCaptureDescriptor(PackRole role, Material material,
                                                                   InstanceType<?> type, boolean embedded) {
        track(currentPipeline());
        PackRole routed = role.forMaterial(material);
        ProgramKey program = velocity(ProgramKey.of(routed, false, type, material, embedded, false, 0));
        return LIGHT_PIPELINES.computeIfAbsent(lightCaptureKey(program, material),
                k -> register(routed, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), false,
                        true, k.cull(), k.polygonOffset(), embedded), k, true));
    }

    private static RenderPipeline indirectLightCaptureDescriptor(PackRole role, Material material,
                                                                 boolean embedded) {
        track(currentPipeline());
        PackRole routed = role.forMaterial(material);
        ProgramKey program = velocity(bindless(ProgramKey.of(routed, true, null, material, embedded, false,
                InstanceTypeIds.snapshot().types().size())));
        return LIGHT_PIPELINES.computeIfAbsent(lightCaptureKey(program, material),
                k -> register(routed, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), false,
                        true, k.cull(), k.polygonOffset(), k.program().bindless()), k, true));
    }

    /**
     * Colour-program positions; depth-only redraw.
     */
    private static RenderPipeline instancingDepthFillDescriptor(PackRole role, Material material,
                                                                InstanceType<?> type, boolean embedded) {
        track(currentPipeline());
        ProgramKey program = ProgramKey.of(role, false, type, material, embedded, false, 0);
        return PIPELINES.computeIfAbsent(depthFillKey(program, material),
                k -> register(role, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), true, false,
                        k.cull(), k.polygonOffset(), embedded), k));
    }

    private static RenderPipeline indirectDepthFillDescriptor(PackRole role, Material material, boolean embedded) {
        track(currentPipeline());
        ProgramKey program = bindless(ProgramKey.of(role, true, null, material, embedded, false,
                InstanceTypeIds.snapshot().types().size()));
        return PIPELINES.computeIfAbsent(depthFillKey(program, material),
                k -> register(role, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), true, false,
                        k.cull(), k.polygonOffset(), k.program().bindless()), k));
    }

    /**
     * OIT producer; {@code role} = TRANSLUCENT/SHADOW.
     */
    private static RenderPipeline instancingOitDescriptor(PackRole role, OitPass pass, Material material,
                                                          InstanceType<?> type, boolean embedded) {
        track(currentPipeline());
        ProgramKey program = ProgramKey.of(role, false, type, material, embedded, false, 0)
                                       .withOit(pass);
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(role, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), embedded), k));
    }

    private static RenderPipeline indirectOitDescriptor(PackRole role, OitPass pass, Material material,
                                                        boolean embedded) {
        track(currentPipeline());
        ProgramKey program = bindless(ProgramKey.of(role, true, null, material, embedded, false,
                InstanceTypeIds.snapshot().types().size()).withOit(pass));
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(role, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), k.program().bindless()), k));
    }

    /**
     * Contract translucent framebuffer; after OIT producer passes.
     */
    public static RenderPipeline oitComposite(boolean shadow) {
        int index = shadow ? 1 : 0;
        if (COMPOSITES[index] == null) {
            COMPOSITES[index] = RenderPipeline.builder()
                                              .withLocation(ResourceUtil.rl(
                                                      "pipeline/iris/oit_composite" + (shadow ? "_shadow" : "")))
                                              .withVertexShader(GUEST_SHADER)
                                              .withFragmentShader(GUEST_SHADER)
                                              .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                              .withDepthStencilState(
                                                      new DepthStencilState(CompareOp.ALWAYS_PASS, false, 0.0f, 0.0f))
                                              .withCull(false)
                                              .withColorTargetState(new ColorTargetState(Optional.of(COMPOSITE_BLEND),
                                                      GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                                              .build();
            COMPOSITE_SHADOW.put(COMPOSITES[index], shadow);
        }
        return COMPOSITES[index];
    }

    /**
     * After composite; main reversed-Z, shadow forward-Z unless the pack takes reversed depth natively.
     */
    public static RenderPipeline oitDepth(boolean shadow) {
        boolean reversed = !shadow || NativeDepth.active();
        int index = shadow ? reversed ? 2 : 1 : 0;
        if (DEPTHS[index] == null) {
            DEPTHS[index] = RenderPipeline.builder()
                                          .withLocation(ResourceUtil.rl("pipeline/iris/oit_depth"
                                                  + (shadow ? reversed ? "_shadow_reversed" : "_shadow" : "")))
                                          .withVertexShader(GUEST_SHADER)
                                          .withFragmentShader(GUEST_SHADER)
                                          .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                          .withDepthStencilState(new DepthStencilState(reversed
                                                  ? CompareOp.GREATER_THAN_OR_EQUAL : CompareOp.LESS_THAN_OR_EQUAL,
                                                  true, 0.0f, 0.0f))
                                          .withCull(false)
                                          .withColorTargetState(new ColorTargetState(Optional.empty(),
                                                  GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                                          .build();
            DEPTH_SHADOW.put(DEPTHS[index], shadow);
        }
        return DEPTHS[index];
    }

    /**
     * Nearest native translucent metadata, before the pack's composite sequence.
     */
    public static RenderPipeline forwardWater(GpuFormat format) {
        if (FORWARD_WATER == null || FORWARD_WATER_FORMAT != format) {
            FORWARD_WATER = RenderPipeline.builder()
                                          .withLocation(ResourceUtil.rl("pipeline/iris/forward_water"))
                                          .withVertexShader(GUEST_SHADER)
                                          .withFragmentShader(GUEST_SHADER)
                                          .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                          .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,
                                                  true, 0.0f, 0.0f))
                                          .withCull(false)
                                          .withColorTargetState(new ColorTargetState(Optional.empty(), format,
                                                  ColorTargetState.WRITE_ALL))
                                          .build();
            FORWARD_WATER_FORMAT = format;
        }
        return FORWARD_WATER;
    }

    /**
     * Native pipelines follow {@code GlCompat}; guests carry {@link ProgramKey#bindless}.
     */
    public static boolean bindless(RenderPipeline pipeline) {
        ProgramKey key = PROGRAM_KEYS.get(pipeline);
        return key == null ? GlCompat.SUPPORTS_BINDLESS_TEXTURES : key.bindless();
    }

    // Geometry/tessellation stages would have to relay the albedo varyings.
    private static ProgramKey bindless(ProgramKey key) {
        if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES || IrisRuntime.EXPERIMENTAL
                || !(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            return key;
        }
        if (bindlessOwner != pipeline) {
            bindlessOwner = pipeline;
            BINDLESS.clear();
        }
        return BINDLESS.computeIfAbsent(key, k -> {
            try {
                return hasExtraStages(GuestProgramResolver.resolve(pipeline, k).source()) ? k : k.withBindless();
            } catch (BackendUnavailableException _) {
                return k;
            }
        });
    }

    /**
     * Marks the current pack's colour programs reading {@code at_velocity}: their draws bind instance history. Shadow
     * views keep no previous matrices; static geometry needs none.
     */
    private static ProgramKey velocity(ProgramKey key) {
        if (IrisRuntime.EXPERIMENTAL || key.role().shadow
                || key.type() != null && GuestShaders.STATIC_GEOMETRY.contains(key.type())
                || !(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            return key;
        }
        if (velocityOwner != pipeline) {
            velocityOwner = pipeline;
            velocityDemanded = false;
            VELOCITY.clear();
        }
        return VELOCITY.computeIfAbsent(key, k -> {
            try {
                if (!GuestProgramResolver.resolve(pipeline, k).source().getVertexSource()
                                         .map(vertex -> VELOCITY_INPUT.matcher(vertex).find()).orElse(false)) {
                    return k;
                }
            } catch (BackendUnavailableException _) {
                return k;
            }
            velocityDemanded = true;
            return k.withVelocity();
        });
    }

    /**
     * Whether the current pack has a program reading {@code at_velocity}: guest draw managers then keep instance
     * history. Render thread.
     */
    public static boolean velocityDemanded() {
        return velocityDemanded && velocityOwner == Iris.getPipelineManager().getPipelineNullable();
    }

    /**
     * Whether {@code pipeline} binds {@link InstanceAssemblerComponent#HISTORY_BUFFER_NAME} (instancing).
     */
    public static boolean history(RenderPipeline pipeline) {
        ProgramKey key = PROGRAM_KEYS.get(pipeline);
        return key != null && key.velocity() && !key.indirect();
    }

    private static PipelineKey materialKey(ProgramKey program, Material material) {
        boolean shadow = program.role().shadow;
        var pipeline = Iris.getPipelineManager()
                           .getPipelineNullable() instanceof IrisRenderingPipeline iris ? iris : null;
        boolean depthWrite = pipeline != null
                ? GuestProgramResolver.depthWrite(pipeline, program, material.writeMask().depth())
                : shadow || program.role() == PackRole.ENTITIES || material.writeMask().depth();
        return new PipelineKey(program, material.transparency(), material.depthTest(), depthWrite,
                material.writeMask().color(),
                !shadow && material.backfaceCulling(), !shadow && material.polygonOffset());
    }

    private static PipelineKey depthFillKey(ProgramKey program, Material material) {
        return new PipelineKey(program, material.transparency(), material.depthTest(), true, false,
                material.backfaceCulling(), material.polygonOffset());
    }

    private static PipelineKey lightCaptureKey(ProgramKey program, Material material) {
        return new PipelineKey(program, material.transparency(), DepthTest.LEQUAL, false, true,
                material.backfaceCulling(), material.polygonOffset());
    }

    private static CompareOp shadowZ(DepthTest depthTest) {
        return NativeDepth.active() ? depthTest.compareOp : forwardZ(depthTest);
    }

    private static CompareOp forwardZ(DepthTest depthTest) {
        return switch (depthTest) {
            case OFF, ALWAYS -> CompareOp.ALWAYS_PASS;
            case NEVER -> CompareOp.NEVER_PASS;
            case LESS -> CompareOp.LESS_THAN;
            case EQUAL -> CompareOp.EQUAL;
            case LEQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            case GREATER -> CompareOp.GREATER_THAN;
            case NOTEQUAL -> CompareOp.NOT_EQUAL;
            case GEQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
        };
    }

    private static RenderPipeline crumblingDescriptor(Material crumblingMaterial, InstanceType<?> type,
                                                      boolean indirect) {
        track(currentPipeline());
        ProgramKey program = velocity(ProgramKey.of(PackRole.DAMAGED, indirect, type, crumblingMaterial, false, true,
                0));
        PipelineKey key = new PipelineKey(program, crumblingMaterial.transparency(), crumblingMaterial.depthTest(),
                false, true, crumblingMaterial.backfaceCulling(), false);
        return PIPELINES.computeIfAbsent(key,
                k -> register(PackRole.DAMAGED, CrumblingPipelines.stateBuilder(k.depthTest(), k.cull(), indirect),
                        k));
    }

    private static RenderPipeline register(PackRole role, RenderPipeline.Builder builder, PipelineKey key) {
        return register(role, builder, key, false);
    }

    private static RenderPipeline register(PackRole role, RenderPipeline.Builder builder, PipelineKey key,
                                           boolean snapshot) {
        if (role.shadow) {
            // Shadow state is forward-Z (cleared to 1) unless the pack takes raw reversed depth; registration
            // compensates Iris's native bind policy.
            builder.withDepthStencilState(new DepthStencilState(shadowZ(key.depthTest()), true, 0.0f, 0.0f))
                   .withCull(false);
        }
        OitPass oit = key.program()
                         .oit();
        if (oit != null) {
            boolean offset = key.polygonOffset();
            builder.withDepthStencilState(new DepthStencilState(role.shadow ? shadowZ(key.depthTest())
                           : key.depthTest().compareOp, false, offset ? 1.0f : 0.0f, offset ? 10.0f : 0.0f))
                   .withColorTargetState(new ColorTargetState(
                           Optional.of(oit == OitPass.DEPTH_RANGE ? MAX_BLEND : ADD_BLEND), GpuFormat.RGBA8_UNORM,
                           ColorTargetState.WRITE_ALL));
        }
        if (key.program().velocity() && !key.program().indirect()) {
            builder.withBindGroupLayout(BindGroupLayout.builder()
                                                       .withUniform(InstanceAssemblerComponent.HISTORY_BUFFER_NAME,
                                                               UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
                                                       .withUniform(InstancedInstancer.MOVED_TEXELS_NAME,
                                                               UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                                                       .build());
        }
        RenderPipeline pipeline = builder.withVertexBinding(1, GuestVertexExtras.FORMAT)
                                         .withLocation(ResourceUtil.rl("pipeline/iris/" + key.cacheName()
                                                 + (snapshot ? "_snapshot" : "")))
                                         .withVertexShader(GUEST_SHADER)
                                         .withFragmentShader(GUEST_SHADER)
                                         .build();
        PROGRAM_KEYS.put(pipeline, key.program());
        if (snapshot) SNAPSHOT_PIPELINES.add(pipeline);
        int typeGen = key.program().typeGen();
        if (typeGen > 0) {
            VersionKey version = new VersionKey(key.withTypeGen(0), snapshot);
            VERSION_KEYS.put(pipeline, version);
            VERSION_DESCRIPTORS.computeIfAbsent(version, _ -> new ReferenceOpenHashSet<>()).add(pipeline);
            PipelineVersions.track(pipeline, new ProgramGeneration(typeGen, Integer.MAX_VALUE, Integer.MAX_VALUE));
        }
        return pipeline;
    }

    /**
     * Guest pipeline or {@code null}; current Iris pipeline ownership.
     */
    public static @Nullable CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        return compiled(pipeline, null);
    }

    private static @Nullable CompiledRenderPipeline compiled(RenderPipeline pipeline,
                                                             @Nullable IrisRenderingPipeline current) {
        ProgramKey key = PROGRAM_KEYS.get(pipeline);
        Boolean compositeShadow = COMPOSITE_SHADOW.get(pipeline);
        Boolean depthShadow = DEPTH_SHADOW.get(pipeline);
        boolean water = pipeline == FORWARD_WATER;
        TerrainPipelines.Kind terrainKind = null;
        List<Identifier> overlays = List.of();
        if (GuestTerrainGate.enabled() && Iris.isPackInUseQuick()) {
            TerrainOverlayPipelines.Overlay overlay = TerrainOverlayPipelines.overlay(pipeline);
            terrainKind = TerrainPipelines.terrainKind(overlay == null ? pipeline : overlay.base());
            if (overlay != null) overlays = overlay.sources();
        }
        if (key == null && compositeShadow == null && depthShadow == null && terrainKind == null && !water) {
            return null;
        }
        if (current == null) current = currentPipeline();
        track(current);
        boolean mesh = terrainKind != null && GuestTerrainGate.meshActive;
        Map<RenderPipeline, CompiledRenderPipeline> cache = mesh ? MESH_COMPILED : COMPILED;
        CompiledRenderPipeline compiled = cache.get(pipeline);
        if (compiled == null) {
            GuestProgram program = key != null ? program(current, pipeline, key)
                    : terrainKind != null ? terrain(current, pipeline, terrainKind, mesh, overlays)
                    : compositeShadow != null ? composite(current, pipeline, compositeShadow)
                    : depthShadow != null ? compositeDepth(current, pipeline, depthShadow)
                    : forwardWater(current, pipeline);
            compiled = registerCompiled(pipeline, program);
            cache.put(pipeline, compiled);
        }
        return compiled;
    }

    private static CompiledRenderPipeline registerCompiled(RenderPipeline pipeline, GuestProgram program) {
        #if MC >= 26.3
        return RenderPipelineCompiler.register(pipeline, program,
                program.namedUniforms(pipeline.getBindGroupLayouts()), nativeDepth(pipeline, program),
                PROGRAM_KEYS.containsKey(pipeline) ? GuestShaders.ATTRIBUTES : null);
        #else
        return new GlRenderPipeline(pipeline, program);
        #endif
    }

    #if MC >= 26.3
    private static @Nullable DepthStencilState nativeDepth(RenderPipeline pipeline, GuestProgram program) {
        DepthStencilState state = pipeline.getDepthStencilState();
        if (!program.shadow() || state == null || NativeDepth.active()) return state;
        // Compat with Iris 1.11.7: it inverts every native depth compare while a forward-depth pack renders shadows.
        // Guests already provide forward-Z state, so compensate here before that bind-time inversion, including
        // claimed terrain.
        CompareOp nativeCompare = switch (state.depthTest()) {
            case LESS_THAN -> CompareOp.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            case GREATER_THAN -> CompareOp.LESS_THAN;
            case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            default -> state.depthTest();
        };
        return new DepthStencilState(nativeCompare, state.writeDepth(), state.depthBiasScaleFactor(),
                state.depthBiasConstant());
    }
    #endif

    /**
     * The current pack's terrain guest program, compiled on demand; render thread only, valid until pack release.
     */
    public static GuestProgram program(RenderPipeline pipeline) {
        return program(compiled(pipeline));
    }

    private static GuestProgram program(CompiledRenderPipeline pipeline) {
        #if MC >= 26.3
        return (GuestProgram) ((GlRenderPipeline) ((FrontendRenderPipeline) pipeline).backendRenderPipeline())
                .program();
        #else
        return (GuestProgram) ((GlRenderPipeline) pipeline).program();
        #endif
    }

    /**
     * Resource apply/dimension entry; before visual draws.
     */
    public static void warmUp(IrisRenderingPipeline pipeline) {
        if (warmed == pipeline) return;
        if (glintOwner != pipeline) {
            glintOwner = pipeline;
            ContractProgramSet contracts = contractSet(pipeline);
            boolean nextNativeGlint = contracts.flywheel$nativeGlint();
            if (nativeGlint != nextNativeGlint) {
                nativeGlint = nextNativeGlint;
                // Compat with Iris: shader-only reloads can change glint's stage without changing the vertex format.
                if (Minecraft.getInstance().level != null) Minecraft.getInstance().levelExtractor.allChanged();
            }
        }
        if (FlwConfig.INSTANCE.backend() == BackendManager.offBackend()) return;
        if (FlwPrograms.SOURCES == null || !GlCompat.SUPPORTS_INSTANCING) return;
        GuestShaders.clearTransformMemos();
        ShaderWarmup.seedRegistrations();
        if (BackendConfig.INSTANCE.shaderCompilation() == ShaderCompilationMode.ON_DEMAND) {
            track(pipeline);
            warmed = pipeline;
            return;
        }
        try {
            boolean indirect = FlwConfig.INSTANCE.backend() != Backends.INSTANCING
                    && FlwConfig.INSTANCE.backend() != IrisBackends.IRIS_INSTANCING
                    && GlCompat.SUPPORTS_INDIRECT && IndirectPrograms.allLoaded()
                    && !BackendRecovery.isRejected(IrisBackends.IRIS_INDIRECT);
            if (indirect) {
                try {
                    warmUpTier(pipeline, true);
                    return;
                } catch (BackendUnavailableException failure) {
                    if (Boolean.getBoolean("crankshaft.shader.strict")) throw failure;
                    BackendRecovery.reject(IrisBackends.IRIS_INDIRECT, failure);
                    track(null);
                }
            }
            if (!InstancingPrograms.allLoaded()
                    || BackendRecovery.isRejected(IrisBackends.IRIS_INSTANCING)) return;
            try {
                warmUpTier(pipeline, false);
            } catch (BackendUnavailableException failure) {
                if (Boolean.getBoolean("crankshaft.shader.strict")) throw failure;
                BackendRecovery.reject(IrisBackends.IRIS_INSTANCING, failure);
                track(null);
            }
        } finally {
            if (BackendRecovery.isRejected(BackendManager.currentBackend())) BackendManagerImpl.reselect();
        }
    }

    private static void warmUpTier(IrisRenderingPipeline pipeline, boolean indirect) {
        List<InstanceType<?>> types = indirect ? Collections.singletonList(null) : ShaderWarmupRegistry.types();
        List<Material> materials = ShaderWarmupRegistry.materials();
        List<Material> particles = ShaderWarmupRegistry.particlesTranslucentMaterials();
        boolean shadows = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shadowTargets().get() != null;
        long start = System.nanoTime();
        track(pipeline);
        lightDemandPrograms(pipeline);
        if (sortedBillboardBlendEnabled(pipeline)) sortedBlendProgram(pipeline);
        int before = PROGRAMS.size();
        Map<LinkKey, List<RenderPipeline>> pending = new HashMap<>();
        Set<RenderPipeline> requested = new ReferenceOpenHashSet<>();
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            batch.splash(0);
            for (InstanceType<?> type : types) {
                for (Material material : materials) {
                    for (PackRole role : PackRole.values()) {
                        if (role == PackRole.DAMAGED || role == PackRole.GLINT
                                || role == PackRole.PARTICLES_TRANSLUCENT || role == PackRole.PARTICLES_FLATTEN
                                || role == PackRole.PARTICLES_HANDOFF) continue;
                        warmRole(pipeline, batch, pending, requested, indirect, shadows, type, material, role);
                    }
                }
                // Only materials whose models take that route.
                boolean singleRecord = singleRecordParticles(pipeline);
                for (Material material : particles) {
                    for (PackRole role : singleRecord ? List.of(PackRole.PARTICLES_FLATTEN, PackRole.PARTICLES_HANDOFF)
                            : List.of(PackRole.PARTICLES_TRANSLUCENT)) {
                        warmRole(pipeline, batch, pending, requested, indirect, shadows, type, material, role);
                    }
                }
            }
            batch.finish(() -> {
            });
        }
        for (RenderPipeline descriptor : requested) {
            if (VERSION_KEYS.containsKey(descriptor)) newest(descriptor);
        }
        FlwBackend.LOGGER.info("guest pipeline warm-up took {}ms ({} new programs, {} + {} material variants, "
                        + "{} pipeline descriptors)",
                (System.nanoTime() - start) / 1_000_000, PROGRAMS.size() - before, materials.size(),
                particles.size(), requested.size());
        if (shadows && FarVisuals.available()) {
            FarStaticShadows.warmUp();
            for (var material : materials) {
                if (material.writeMask().depth() && !emissive(material)
                        && sourceAvailable(pipeline, staticShadowKey(material)))
                    staticShadowProgram(pipeline, material);
            }
        }
        warmed = pipeline;
    }

    private static boolean materialRole(Material material, PackRole role) {
        boolean additive = OitTransparency.additive(material)
                || material.transparency() == Transparency.ADDITIVE
                || material.transparency() == Transparency.LIGHTNING;
        boolean translucent = material.transparency() == Transparency.TRANSLUCENT
                || material.transparency() == Transparency.TRANSLUCENT_ALPHA_REPLACE
                || OitTransparency.orderIndependent(material)
                || material.transparency() == Transparency.GLINT && nativeGlint;
        return switch (role) {
            case SOLID, BLOCK_ENTITY, TERRAIN, ENTITY_SOLID -> !additive && !translucent;
            case TRANSLUCENT, TERRAIN_TRANSLUCENT, ENTITY_TRANSLUCENT -> !additive && translucent;
            case ADDITIVE -> additive;
            default -> true;
        };
    }

    private static void warmRole(IrisRenderingPipeline pipeline, GlCompilationBatch batch,
                                 Map<LinkKey, List<RenderPipeline>> pending, Set<RenderPipeline> requested,
                                 boolean indirect, boolean shadows, @Nullable InstanceType<?> type, Material material,
                                 PackRole role) {
        if (!materialRole(material, role) || role.shadow && (!shadows || emissive(material))) return;
        for (boolean embedded : new boolean[]{false, true}) {
            RenderPipeline draw = indirect ? indirectDescriptor(role, material, null, embedded)
                    : instancingDescriptor(role, material, type, embedded);
            ProgramKey key = PROGRAM_KEYS.get(draw);
            ProgramSource contract = GuestProgramResolver.contractSource(pipeline, key);
            if (contract == null
                    && ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver().resolveNullable(
                    key.role().programId) == null)
                continue;
            prepare(draw, pipeline, batch, pending, requested);
            if (indirect && !role.shadow && orderedBillboardCapture(pipeline)
                    && contract != null
                    && ContractProgram.byName(contract.getName()) == ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT) {
                prepare(indirectDescriptor(role, material, InstanceTypes.BILLBOARD, embedded), pipeline, batch,
                        pending, requested);
            }
            if (indirect && !role.shadow && sortedBillboardBlendEnabled(pipeline) && contract != null
                    && ContractProgram.byName(contract.getName()) == ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT) {
                prepare(indirectSortedBillboardDescriptor(role, material, embedded, SortedBlendPass.FAST), pipeline,
                        batch, pending, requested);
                prepare(indirectSortedBillboardDescriptor(role, material, embedded, SortedBlendPass.FALLBACK),
                        pipeline, batch, pending, requested);
            }
            if (role == PackRole.ADDITIVE && emissive(material) && lateLightCapture(pipeline)) {
                prepare(indirect ? indirectLightCaptureDescriptor(role, material, embedded)
                                : instancingLightCaptureDescriptor(role, material, type, embedded), pipeline, batch, pending,
                        requested);
            }
            if (role != PackRole.TRANSLUCENT && role != PackRole.SHADOW) continue;
            boolean oit = OitTransparency.orderIndependent(material) && !OitTransparency.additive(material)
                    && oitActive(pipeline, role.shadow);
            if (!oit) continue;
            prepare(indirect ? indirectDepthFillDescriptor(role, material, embedded)
                            : instancingDepthFillDescriptor(role, material, type, embedded), pipeline, batch, pending,
                    requested);
            for (OitPass pass : OitPass.values()) {
                prepare(indirect ? indirectOitDescriptor(role, pass, material, embedded)
                                : instancingOitDescriptor(role, pass, material, type, embedded), pipeline, batch, pending,
                        requested);
            }
        }
    }

    private static void prepare(RenderPipeline renderPipeline, IrisRenderingPipeline pipeline, GlCompilationBatch batch,
                                Map<LinkKey, List<RenderPipeline>> pending, Set<RenderPipeline> requested) {
        if (!requested.add(renderPipeline)) return;
        RETIRED.remove(renderPipeline);
        ResolvedProgram resolved = resolved(pipeline, renderPipeline, PROGRAM_KEYS.get(renderPipeline));
        GuestProgram cached = PROGRAMS.get(resolved.key());
        if (cached != null) {
            COMPILED.put(renderPipeline, registerCompiled(renderPipeline, cached));
            return;
        }
        List<RenderPipeline> references = pending.get(resolved.key());
        if (references != null) {
            references.add(renderPipeline);
            return;
        }
        List<RenderPipeline> outputs = new ArrayList<>();
        outputs.add(renderPipeline);
        pending.put(resolved.key(), outputs);
        PreparedProgram prepared = prepareLink(pipeline, renderPipeline, resolved.key(), resolved.source());
        linkBatch(batch, prepared.label(), prepared.stages(), GuestShaders.ATTRIBUTES, handle -> {
            GuestProgram program = prepared.publish().apply(handle);
            PROGRAMS.put(resolved.key(), program);
            for (RenderPipeline output : outputs) COMPILED.put(output, registerCompiled(output, program));
        });
    }

    public static void warmCurrent() {
        if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
            track(null);
            warmUp(pipeline);
        }
    }

    private static boolean emissive(Material material) {
        return OitTransparency.additive(material)
                || material.transparency() == Transparency.ADDITIVE
                || material.transparency() == Transparency.LIGHTNING
                || material.transparency() == Transparency.GLINT;
    }

    private static GuestProgram terrain(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                        TerrainPipelines.Kind kind, boolean mesh, List<Identifier> overlays) {
        boolean cutout = kind.cutout();
        boolean shadow = kind.shadow();
        int oitPass = kind.oitPass();
        ProgramId programId = oitPass >= 0 ? ProgramId.Water
                : shadow ? (cutout ? ProgramId.ShadowCutout : ProgramId.ShadowSolid)
                : (cutout ? ProgramId.TerrainCutout : ProgramId.TerrainSolid);
        ProgramSource nativeSource = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()
                                                                               .resolveNullable(programId);
        if (nativeSource == null) {
            throw new BackendUnavailableException("Shaderpack has no program for " + programId);
        }
        var forward = contractSet(pipeline).flywheel$forwardOit();
        boolean forwardProducer = forward != null && oitPass >= 0 && oitPass < 3;
        ProgramSource source = forwardProducer ? forward.source(nativeSource) : nativeSource;
        AlphaTest alpha = forwardProducer ? AlphaTests.OFF : source.getDirectives()
                                                                   .getAlphaTestOverride()
                                                                   .orElse(oitPass >= 0 ? AlphaTests.ONE_TENTH_ALPHA
                                                                           : cutout ? AlphaTests.HALF_ALPHA : AlphaTests.OFF);
        int[] drawBuffers = drawBuffers(source, shadow);
        if (oitPass >= 0 && oitPass < 3) {
            return terrainOit(pipeline, renderPipeline, source, alpha, drawBuffers, oitPass, mesh, overlays);
        }
        String label = "flywheel:iris/" + source.getName() + (oitPass == 3 ? "/terrain_capture" : shadow ? "/shadow_terrain" : "/terrain")
                + (cutout ? "_cutout" : "") + (overlays.isEmpty() ? "" : "_overlay");
        int programId2;
        int meshQuads = 0;
        int taskQuads = 0;
        boolean taskRecovery = false;
        boolean compactSafe = false;
        if (mesh) {
            GuestTerrainMeshShaders.Stages stages = GuestTerrainMeshShaders.build(pipeline, source, alpha, shadow,
                    drawBuffers, null, overlays, oitPass == 3);
            programId2 = linkMeshProgram(label, stages);
            meshQuads = stages.quads();
            taskQuads = stages.taskQuads();
            taskRecovery = stages.taskRecovery();
            compactSafe = stages.compactSafe();
        } else {
            GuestTerrainShaders.Stages stages = GuestTerrainShaders.build(pipeline, source, alpha, shadow, overlays,
                    oitPass == 3);
            programId2 = linkProgram(label,
                    new GuestShaders.Stages(stages.vertex(), stages.geometry(), null, null, stages.fragment()),
                    terrainAttributes());
        }

        GuestProgram program = publishDirect(programId2, id -> new GuestProgram(id, label,
                renderPipeline.getBindGroupLayouts(), false, pipeline, shadow,
                packTarget(pipeline, drawBuffers, shadow), source.getDirectives()
                                                                 .getBlendModeOverride()
                                                                 .orElse(programId.getBlendModeOverride()),
                bufferBlends(source.getDirectives()
                                   .getBufferBlendOverrides(), drawBuffers), alpha, meshTextures(mesh, List.of())));
        program.useTerrainState();
        if (mesh) program.useMesh(meshQuads, taskQuads, taskRecovery, compactSafe);
        return program;
    }

    private static GuestProgram terrainOit(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                           ProgramSource source, AlphaTest alpha, int[] drawBuffers, int oitPass,
                                           boolean mesh, List<Identifier> overlays) {
        ContractProperties properties = Objects.requireNonNull(oitProperties(pipeline, false));
        OitPass pass = OitPass.values()[oitPass];
        int[] oitBuffers = drawBuffers(Objects.requireNonNull(oitSource(pipeline, false)), false);
        GuestShaders.OitSpec spec = new GuestShaders.OitSpec(pass, properties.oit(false), oitBuffers, false);
        String label = "flywheel:iris/" + source.getName() + "/terrain_oit_" + pass.name().toLowerCase(Locale.ROOT)
                + (overlays.isEmpty() ? "" : "_overlay");
        int programId;
        int meshQuads = 0;
        int taskQuads = 0;
        boolean taskRecovery = false;
        if (mesh) {
            GuestTerrainMeshShaders.Stages stages = GuestTerrainMeshShaders.build(pipeline, source, alpha, false,
                    drawBuffers, spec, overlays, true);
            programId = linkMeshProgram(label, stages);
            meshQuads = stages.quads();
            taskQuads = stages.taskQuads();
            taskRecovery = stages.taskRecovery();
        } else {
            GuestTerrainShaders.Stages stages = GuestTerrainShaders.buildOit(pipeline, source, alpha, drawBuffers,
                    spec, overlays);
            programId = linkProgram(label,
                    new GuestShaders.Stages(stages.vertex(), stages.geometry(), null, null, stages.fragment()),
                    terrainAttributes());
        }

        GuestOitTargets targets = oitTargets(pipeline, false);
        GuestProgram program = publishDirect(programId, id -> new GuestProgram(id, label,
                renderPipeline.getBindGroupLayouts(), false, pipeline, false,
                _ -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                        oitPass == 0 ? targets.depthRangeFbo()
                                : oitPass == 1 ? targets.coefficientsFbo() : targets.accumulateFbo()),
                null, List.of(), alpha, meshTextures(mesh, producerTextures(targets, pass)),
                producerImages(targets, pass)));
        program.useTerrainState();
        if (mesh) program.useMesh(meshQuads, taskQuads, taskRecovery, false);
        return program;
    }

    private static List<GuestProgram.RawTexture> meshTextures(boolean mesh, List<GuestProgram.RawTexture> textures) {
        if (!mesh) return textures;
        List<GuestProgram.RawTexture> result = new ArrayList<>(textures);
        result.add(new GuestProgram.RawTexture("_flw_taskDepth", GL11C.GL_TEXTURE_2D,
                () -> GuestTerrainGate.meshDepthTexture));
        return List.copyOf(result);
    }

    private static String[] terrainAttributes() {
        return TerrainVertexFormat.current()
                                  .getElements()
                                  .stream()
                                  .map(VertexFormatElement::name)
                                  .toArray(String[]::new);
    }

    /**
     * Iris pipeline destruction, render thread; releases owned guest resources.
     */
    public static void release(IrisRenderingPipeline pipeline) {
        if (glintOwner == pipeline) glintOwner = null;
        if (bindlessOwner == pipeline) {
            bindlessOwner = null;
            BINDLESS.clear();
        }
        if (velocityOwner == pipeline) {
            velocityOwner = null;
            velocityDemanded = false;
            VELOCITY.clear();
        }
        if (owner == pipeline) {
            track(null);
        }
        #if MC < 26.3
        GuestLodOcclusion.releaseTerrainDepth();
        #endif
    }

    private static void track(@Nullable IrisRenderingPipeline current) {
        if (current == owner) {
            return;
        }
        LINKING.values()
               .forEach(GlAsyncCompiler.Pending::cancel);
        LINKING.clear();
        RESOLVED.clear();
        PROGRAM_REFERENCES.clear();
        FarStaticShadows.reset();
        STATIC_SHADOW_LINKING.values().forEach(GlAsyncCompiler.Pending::cancel);
        STATIC_SHADOW_LINKING.clear();
        STATIC_SHADOW_PROGRAMS.values().forEach(GuestProgram::close);
        STATIC_SHADOW_PROGRAMS.clear();
        PROGRAMS.values()
                .forEach(GuestProgram::close);
        PROGRAMS.clear();
        COMPILED.entrySet().stream()
                .filter(entry -> !PROGRAM_KEYS.containsKey(entry.getKey()))
                .forEach(entry -> program(entry.getValue()).close());
        MESH_COMPILED.values().forEach(compiled -> program(compiled).close());
        #if MC >= 26.3
        for (var entry : COMPILED.entrySet()) {
            RenderPipelineCompiler.unregister(entry.getKey());
            RenderPipelineCompiler.releaseGuestPipeline((FrontendRenderPipeline) entry.getValue());
        }
        for (var entry : MESH_COMPILED.entrySet()) {
            RenderPipelineCompiler.unregister(entry.getKey());
            RenderPipelineCompiler.releaseGuestPipeline((FrontendRenderPipeline) entry.getValue());
        }
        #endif
        COMPILED.clear();
        MESH_COMPILED.clear();
        VERSION_KEYS.keySet().forEach(PipelineVersions::untrack);
        PIPELINES.clear();
        LIGHT_PIPELINES.clear();
        SNAPSHOT_PIPELINES.clear();
        PROGRAM_KEYS.clear();
        VERSION_KEYS.clear();
        VERSION_DESCRIPTORS.clear();
        VERSIONS.clear();
        RETIRED.clear();
        TRACKED.clear();
        for (int i = 0; i < OIT_TARGETS.length; i++) {
            if (OIT_TARGETS[i] != null) {
                OIT_TARGETS[i].delete();
                OIT_TARGETS[i] = null;
            }
        }
        FLATTEN_TARGET.delete();
        CAPTURE_DEPTH.delete();
        if (LIGHT_DEMAND_PROGRAMS != null) {
            for (var program : LIGHT_DEMAND_PROGRAMS) program.close();
            LIGHT_DEMAND_PROGRAMS = null;
        }
        if (SORTED_BLEND_LATCH != null) {
            SORTED_BLEND_LATCH.close();
            SORTED_BLEND_LATCH = null;
        }
        owner = current;
        warmed = null;
    }

    /**
     * Render-thread draw partitioning; selected when the active Iris pipeline is installed.
     */
    public static boolean nativeGlint() {
        return nativeGlint;
    }

    /**
     * Order-independent draws admitted to contract OIT.
     */
    public static boolean oitActive(IrisRenderingPipeline pipeline, boolean shadow) {
        if (!GuestOitTargets.SUPPORTED) {
            return false;
        }
        ContractProperties properties = oitProperties(pipeline, shadow);
        if (properties == null || !properties.oit(shadow)
                                             .enabled()) {
            return false;
        }
        ProgramSource source = oitSource(pipeline, shadow);
        return source != null && !hasExtraStages(source);
    }

    /**
     * Translucent particles reach the pack composited ({@link PackRole#PARTICLES_FLATTEN}, then
     * {@link PackRole#PARTICLES_HANDOFF}); {@link #prepareFlatten} precedes them each frame.
     */
    public static boolean singleRecordParticles(IrisRenderingPipeline pipeline) {
        return GuestFlattenTarget.SUPPORTED && GuestProgramResolver.singleRecordParticles(pipeline);
    }

    public static void prepareFlatten(IrisRenderingPipeline pipeline) {
        track(pipeline);
        RenderTargets renderTargets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets();
        FLATTEN_TARGET.prepare(renderTargets.getDepthTexture(), renderTargets.getCurrentWidth(),
                renderTargets.getCurrentHeight());
    }

    public static boolean deferredOitActive(IrisRenderingPipeline pipeline) {
        return GuestProgramResolver.deferredCapture(contractSet(pipeline));
    }

    public static boolean orderedBillboardCapture(IrisRenderingPipeline pipeline) {
        return OrderedBillboardCapture.enabled()
                && contractSet(pipeline).flywheel$deferredOit() == DeferredOitProfile.ITERATION;
    }

    public static boolean sortedBillboardBlendEnabled(IrisRenderingPipeline pipeline) {
        return SortedBillboardBlend.enabled()
                && contractSet(pipeline).flywheel$deferredOit() == DeferredOitProfile.ITERATION;
    }

    public static boolean sortedBillboardBlendEligible(IrisRenderingPipeline pipeline, PackRole role,
                                                       Material material) {
        if (!sortedBillboardBlendEnabled(pipeline)) return false;
        var key = ProgramKey.of(role.forMaterial(material), true, InstanceTypes.BILLBOARD, material, false, false,
                InstanceTypeIds.snapshot().types().size());
        var source = GuestProgramResolver.contractSource(pipeline, key);
        return source != null && ContractProgram.byName(source.getName()) == ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT;
    }

    public static boolean orderedBillboardCapture(RenderPipeline renderPipeline) {
        ProgramKey key = PROGRAM_KEYS.get(renderPipeline);
        if (key == null || key.type() != InstanceTypes.BILLBOARD || key.role().shadow || key.oit() != null
                || key.sortedBlendPass() == SortedBlendPass.FAST) {
            return false;
        }
        var pipeline = currentPipeline();
        if (!orderedBillboardCapture(pipeline)) return false;
        var source = GuestProgramResolver.contractSource(pipeline, key);
        return source != null && ContractProgram.byName(source.getName()) == ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT;
    }

    public static boolean lateLightCapture(IrisRenderingPipeline pipeline) {
        if (IrisRuntime.EXPERIMENTAL) return false;
        var profile = contractSet(pipeline).flywheel$deferredOit();
        return profile == DeferredOitProfile.SUNDIAL || profile == DeferredOitProfile.ITERATION;
    }

    public static void captureLightDepth(IrisRenderingPipeline pipeline) {
        if (!lateLightCapture(pipeline)) return;
        track(pipeline);
        var targets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets();
        CAPTURE_DEPTH.capture(targets.getDepthTexture(), targets.getCurrentWidth(), targets.getCurrentHeight());
    }

    private static void lightDemandPrograms(IrisRenderingPipeline pipeline) {
        if (!lateLightCapture(pipeline) || LIGHT_DEMAND_PROGRAMS != null) return;
        var sources = contractSet(pipeline).flywheel$lightDemand();
        String prepare = sources.prepare();
        String query = sources.query();
        if (sortedBillboardBlendEnabled(pipeline)) {
            prepare = SortedBillboardBlend.skipIfFast(prepare);
            query = SortedBillboardBlend.skipIfFast(query);
        }
        LIGHT_DEMAND_PROGRAMS = new GuestProgram[]{lightDemandProgram(pipeline, "prepare", prepare),
                lightDemandProgram(pipeline, "query", query)};
    }

    private static GuestProgram lightDemandProgram(IrisRenderingPipeline pipeline, String stage, String source) {
        String label = "flywheel:iris/light_demand_" + stage;
        String transformed = TransformPatcher.patchCompute(label, source, TextureStage.COMPOSITE_AND_FINAL,
                pipeline.getTextureMap()
                #if MC >= 26.3 , pipeline.getTextureOverrides(TextureStage.COMPOSITE_AND_FINAL) #endif);
        int program = linkStages(label, new int[]{GL43C.GL_COMPUTE_SHADER}, new String[]{transformed}, new String[0]);
        return new GuestProgram(program, label, List.of(), false, pipeline, false, _ -> {
        }, null, List.of(),
                AlphaTests.OFF, List.of(), List.of(), true);
    }

    private static GuestProgram sortedBlendProgram(IrisRenderingPipeline pipeline) {
        if (SORTED_BLEND_LATCH == null) {
            String query = contractSet(pipeline).flywheel$lightDemand().query();
            SORTED_BLEND_LATCH = lightDemandProgram(pipeline, "sorted_billboard_latch",
                    SortedBillboardBlend.latchSource(query, NativeDepth.active()));
            sortedBlendPhase = GL20C.glGetUniformLocation(SORTED_BLEND_LATCH.getProgramId(), "flw_phase");
            sortedBlendRangeCount = GL20C.glGetUniformLocation(SORTED_BLEND_LATCH.getProgramId(),
                    "flw_lightRangeCount");
        }
        return SORTED_BLEND_LATCH;
    }

    public static void prepareSortedBillboardBlend(IrisRenderingPipeline pipeline, int drawBuffer, int rangesBuffer,
                                                   int rangeCount) {
        track(pipeline);
        var program = sortedBlendProgram(pipeline);
        var targets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets();
        GuestSsbos.restore(pipeline);
        GlCompat.pushDebugGroup("flywheel:iris/sorted_billboard_latch");
        try {
            #if MC >= 26.3
            program.iris$setupState(List.of());
            #else
            program.iris$setupState(COMPUTE_TEXTURES, null);
            #endif
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 9, drawBuffer);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 10, rangesBuffer);
            GL30C.glUniform1ui(sortedBlendRangeCount, rangeCount);
            GL30C.glUniform1ui(sortedBlendPhase, 0);
            GL43C.glDispatchCompute(1, 1, 1);
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            GL30C.glUniform1ui(sortedBlendPhase, 1);
            GL43C.glDispatchCompute((targets.getCurrentWidth() + 7) / 8, (targets.getCurrentHeight() + 7) / 8, 1);
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            GL30C.glUniform1ui(sortedBlendPhase, 2);
            GL43C.glDispatchCompute(1, 1, 1);
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    public static void finishSortedBillboardBlend(IrisRenderingPipeline pipeline) {
        var program = sortedBlendProgram(pipeline);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        GuestSsbos.restore(pipeline);
        GlCompat.pushDebugGroup("flywheel:iris/sorted_billboard_demand");
        try {
            #if MC >= 26.3
            program.iris$setupState(List.of());
            #else
            program.iris$setupState(COMPUTE_TEXTURES, null);
            #endif
            GL30C.glUniform1ui(sortedBlendPhase, 3);
            GL43C.glDispatchCompute(1, 1, 1);
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    public static void prepareLightDemand(IrisRenderingPipeline pipeline) {
        if (!lateLightCapture(pipeline) || !BackendManagerImpl.isBackendOn()) return;
        track(pipeline);
        lightDemandPrograms(pipeline);
        var targets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets();
        GuestSsbos.restore(pipeline);
        GlCompat.pushDebugGroup("flywheel:iris/light_demand");
        try {
            for (var program : LIGHT_DEMAND_PROGRAMS) {
                #if MC >= 26.3
                program.iris$setupState(List.of());
                #else
                program.iris$setupState(COMPUTE_TEXTURES, null);
                #endif
                GL43C.glDispatchCompute((targets.getCurrentWidth() + 7) / 8, (targets.getCurrentHeight() + 7) / 8, 1);
                GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            }
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    /**
     * Before producers; prepare targets. Returns coefficient-set presence.
     */
    public static boolean prepareOit(IrisRenderingPipeline pipeline, boolean shadow) {
        track(pipeline);
        GuestOitTargets targets = oitTargets(pipeline, shadow);
        if (shadow) {
            ShadowRenderTargets shadowTargets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shadowTargets()
                                                                                          .get();
            targets.prepare(shadowTargets.getDepthTexture(), shadowTargets.getResolution(),
                    shadowTargets.getResolution());
        } else {
            RenderTargets renderTargets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets();
            targets.prepare(renderTargets.getDepthTexture(), renderTargets.getCurrentWidth(),
                    renderTargets.getCurrentHeight());
        }
        return targets.ranks().length > 0;
    }

    private static GuestOitTargets oitTargets(IrisRenderingPipeline pipeline, boolean shadow) {
        int index = shadow ? 1 : 0;
        if (OIT_TARGETS[index] == null) {
            ContractProperties.Oit oit = Objects.requireNonNull(oitProperties(pipeline, shadow))
                                                .oit(shadow);
            int[] drawBuffers = drawBuffers(Objects.requireNonNull(oitSource(pipeline, shadow)), shadow);
            int[] formats = new int[drawBuffers.length];
            for (int slot = 0; slot < drawBuffers.length; slot++) {
                var buffer = oit.accumulate(drawBuffers[slot]);
                formats[slot] = buffer.coefficient() == ContractProperties.Accumulate.FRONTMOST
                        ? buffer.format().getGlFormat() : GL30C.GL_RGBA32F;
            }
            OIT_TARGETS[index] = new GuestOitTargets(oit.ranks(), formats, oit.forward());
        }
        return OIT_TARGETS[index];
    }

    private static @Nullable ProgramSource oitSource(IrisRenderingPipeline pipeline, boolean shadow) {
        return GuestProgramResolver.oitSource(pipeline, shadow);
    }

    private static boolean hasExtraStages(ProgramSource source) {
        return source.getGeometrySource()
                     .isPresent() || source.getTessControlSource()
                                           .isPresent() || source.getTessEvalSource()
                                                                 .isPresent();
    }

    private static int[] drawBuffers(ProgramSource source, boolean shadow) {
        return shadow && source.getDirectives()
                               .hasUnknownDrawBuffers() ? new int[]{0, 1} : source.getDirectives()
                                                                                  .getDrawBuffers();
    }

    /**
     * Emissive guests: opaque G-buffer before deferred passes.
     */
    public static boolean deferredEmissive(IrisRenderingPipeline pipeline) {
        return (((IrisRenderingPipelineAccessor) pipeline).flywheel$pack())
                .flywheel$deferredEmissive();
    }

    /**
     * Emissive guests: composite light buffer, no own depth.
     */
    public static boolean emissiveLight(IrisRenderingPipeline pipeline) {
        return (((IrisRenderingPipelineAccessor) pipeline).flywheel$pack())
                .flywheel$emissiveLight();
    }

    /**
     * Translucent guests: depth required by pack composite.
     */
    public static boolean deferredTranslucent(IrisRenderingPipeline pipeline) {
        return (((IrisRenderingPipelineAccessor) pipeline).flywheel$pack())
                .flywheel$deferredTranslucent();
    }

    /**
     * Authored Colorwheel contract properties; absent = {@code null}.
     */
    public static @Nullable ContractProperties contractProperties(IrisRenderingPipeline pipeline) {
        return GuestProgramResolver.contractProperties(pipeline);
    }

    private static @Nullable ContractProperties oitProperties(IrisRenderingPipeline pipeline, boolean shadow) {
        return GuestProgramResolver.oitProperties(pipeline, shadow);
    }

    private static ContractProgramSet contractSet(IrisRenderingPipeline pipeline) {
        return ((ProgramFallbackResolverAccessor) ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()).flywheel$programs();
    }

    private static IrisRenderingPipeline currentPipeline() {
        if (!(Iris.getPipelineManager()
                  .getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            throw new IllegalStateException("Guest pipeline requested without an active shaderpack");
        }
        return pipeline;
    }

    private static GuestProgram program(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                        ProgramKey key) {
        ResolvedProgram resolved = resolved(pipeline, renderPipeline, key);
        return PROGRAMS.computeIfAbsent(resolved.key(), k -> link(pipeline, renderPipeline, k, resolved.source()));
    }

    private static ResolvedProgram resolved(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                            ProgramKey key) {
        return RESOLVED.computeIfAbsent(renderPipeline, _ -> {
            ResolvedProgram resolved = resolveProgram(pipeline, renderPipeline, key);
            PROGRAM_REFERENCES.merge(resolved.key(), 1, Integer::sum);
            return resolved;
        });
    }

    static @Nullable GuestProgram staticShadowProgram(IrisRenderingPipeline pipeline, Material material) {
        var requested = staticShadowKey(material);
        var resolved = GuestProgramResolver.resolve(pipeline, requested);
        var key = new LinkKey(resolved.program(), resolved.contract(), GuestShaders.Capture.EARLY);
        var program = STATIC_SHADOW_PROGRAMS.get(key);
        if (program != null || STATIC_SHADOW_LINKING.containsKey(key)) return program;
        var source = resolved.source();
        var state = GuestProgramResolver.state(pipeline, resolved.program(), resolved.contract(), source);
        var stages = GuestShaders.buildStaticShadow(pipeline, source, state.alpha(), resolved.program(),
                resolved.contract() != null);
        String label = "flywheel:iris/" + source.getName() + "/far_static_shadow/" + resolved.program().cacheName();
        int[] targets = drawBuffers(source, true);
        IntFunction<GuestProgram> publish = handle -> new GuestProgram(handle, label,
                FarStaticShadows.LAYOUTS, false, pipeline, true, packTarget(pipeline, targets, true), state.blend(),
                bufferBlends(state.bufferBlends(), targets), state.alpha(), List.of());
        if (!GlAsyncCompiler.available()) {
            program = publishDirect(linkProgram(label, stages), publish);
            STATIC_SHADOW_PROGRAMS.put(key, program);
            return program;
        }
        DriverStages driver = driverStages(label, stages);
        String[] labels = new String[driver.types().length];
        Arrays.fill(labels, label);
        STATIC_SHADOW_LINKING.put(key, GlAsyncCompiler.submit(label, driver.types(), driver.sources(), labels,
                GuestShaders.ATTRIBUTES, null, null, handle -> {
                    STATIC_SHADOW_LINKING.remove(key);
                    STATIC_SHADOW_PROGRAMS.put(key, publishDirect(handle, publish));
                }, () -> STATIC_SHADOW_LINKING.remove(key)));
        return null;
    }

    private static ProgramKey staticShadowKey(Material material) {
        return ProgramKey.of(PackRole.SHADOW, false, InstanceTypes.TRANSFORMED,
                material, false, false, 0);
    }

    private static boolean sourceAvailable(IrisRenderingPipeline pipeline, ProgramKey key) {
        return GuestProgramResolver.contractSource(pipeline, key) != null
                || ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()
                                                             .resolveNullable(key.role().programId) != null;
    }

    private static ResolvedProgram resolveProgram(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                                  ProgramKey key) {
        var resolved = GuestProgramResolver.resolve(pipeline, key);
        DepthStencilState depth = renderPipeline.getDepthStencilState();
        var capture = SNAPSHOT_PIPELINES.contains(renderPipeline) ? GuestShaders.Capture.SNAPSHOT
                : GuestShaders.capture(resolved.source(), resolved.program(), depth != null && depth.writeDepth(),
                depth != null && depth.depthTest() != CompareOp.ALWAYS_PASS, renderPipeline.getLocation().toString());
        return new ResolvedProgram(new LinkKey(resolved.program(), resolved.contract(), capture), resolved.source());
    }

    private static GuestProgram link(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline, LinkKey key,
                                     ProgramSource source) {
        PreparedProgram prepared = prepareLink(pipeline, renderPipeline, key, source);
        return publishDirect(linkProgram(prepared.label(), prepared.stages()), prepared.publish());
    }

    private static GuestProgram publishDirect(int handle, IntFunction<GuestProgram> publish) {
        try {
            return publish.apply(handle);
        } catch (BackendUnavailableException failure) {
            GlStateManager.glDeleteProgram(handle);
            throw failure;
        }
    }

    private static PreparedProgram prepareLink(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                               LinkKey link, ProgramSource source) {
        ProgramKey key = link.program();
        ContractProgram contract = link.contract();
        var state = GuestProgramResolver.state(pipeline, key, contract, source);
        AlphaTest alpha = state.alpha();
        boolean shadow = key.role().shadow;
        int[] drawBuffers = key.sortedBlendPass() == SortedBlendPass.FAST ? new int[]{19} : drawBuffers(source, shadow);
        OitPass pass = key.oit();
        GuestShaders.OitSpec oit = pass == null ? null : new GuestShaders.OitSpec(pass,
                Objects.requireNonNull(oitProperties(pipeline, shadow)).oit(shadow), drawBuffers, shadow);
        GuestShaders.Stages stages = GuestShaders.build(pipeline, source, alpha, key, contract != null, oit,
                state.grayscale(), link.capture());

        String label = "flywheel:iris/" + source.getName() + "/" + key.cacheName()
                + (link.capture() == GuestShaders.Capture.SNAPSHOT ? "_snapshot" : "");
        AlphaTest finalAlpha = alpha;

        if (oit != null) {
            GuestOitTargets targets = oitTargets(pipeline, shadow);
            int fboIndex = pass.ordinal();
            return new PreparedProgram(label, stages, programId -> new GuestProgram(programId, label,
                    renderPipeline.getBindGroupLayouts(), false, pipeline, shadow,
                    _ -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                            fboIndex == 0 ? targets.depthRangeFbo()
                                    : fboIndex == 1 ? targets.coefficientsFbo() : targets.accumulateFbo()), null,
                    List.of(), finalAlpha, producerTextures(targets, pass), producerImages(targets, pass)));
        }

        List<BufferBlendInformation> bufferBlendInformation = state.bufferBlends();
        BlendModeOverride blend = state.blend();
        BlendModeOverride finalBlend = key.sortedBlendPass() == SortedBlendPass.FAST
                ? new BlendModeOverride(new BlendMode(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                GL11C.GL_ZERO, GL11C.GL_ONE_MINUS_SRC_ALPHA)) : blend;
        GuestProgram.Target target = key.role() == PackRole.PARTICLES_FLATTEN ? _ -> FLATTEN_TARGET.bind()
                : packTarget(pipeline, drawBuffers, shadow, link.capture() == GuestShaders.Capture.SNAPSHOT);
        List<GuestProgram.RawTexture> textures = key.role() == PackRole.PARTICLES_HANDOFF
                ? List.of(new GuestProgram.RawTexture(GuestParticleFlatten.TARGET, GL11C.GL_TEXTURE_2D,
                        FLATTEN_TARGET::texture),
                new GuestProgram.RawTexture(GuestParticleFlatten.TARGET_IDS, GL11C.GL_TEXTURE_2D,
                        FLATTEN_TARGET::ids)) : List.of();
        return new PreparedProgram(label, stages, programId -> new GuestProgram(programId, label,
                renderPipeline.getBindGroupLayouts(), key.crumbling(), pipeline, shadow, target, finalBlend,
                key.role() == PackRole.PARTICLES_FLATTEN ? GuestParticleFlatten.BUFFER_BLENDS.stream()
                                                                                             .map(entry -> new BufferBlendOverride(
                                                                                                     entry.index(),
                                                                                                     entry.blendMode()))
                                                                                             .toList()
                        : key.sortedBlendPass() == SortedBlendPass.FAST ? List.of()
                        : bufferBlends(bufferBlendInformation, drawBuffers), finalAlpha, textures));
    }

    private static GuestProgram composite(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                          boolean shadow) {
        ContractProperties properties = Objects.requireNonNull(oitProperties(pipeline, shadow));
        ProgramSource source = Objects.requireNonNull(oitSource(pipeline, shadow));
        ContractProgram program = Objects.requireNonNull(ContractProgram.byName(source.getName()));
        int[] drawBuffers = drawBuffers(source, shadow);
        GuestShaders.OitSpec spec = new GuestShaders.OitSpec(null, properties.oit(shadow), drawBuffers, shadow);
        String label = "flywheel:iris/" + source.getName() + "/oit_composite";
        int programId = linkProgram(label, new GuestShaders.Stages(GuestOitCodegen.COMPOSITE_VERTEX, null, null, null,
                GuestOitCodegen.compositeFragment(spec)));

        GuestOitTargets targets = oitTargets(pipeline, shadow);
        List<GuestProgram.RawTexture> textures = new ArrayList<>();
        textures.add(new GuestProgram.RawTexture("_flw_depthRange", GL11C.GL_TEXTURE_2D, targets::depthRange));
        var forward = spec.oit().forward();
        if (forward != null) textures.add(new GuestProgram.RawTexture("_flw_forwardBackground",
                GL11C.GL_TEXTURE_2D, targets::forwardBackground));
        for (int set = 0; set < targets.ranks().length; set++) {
            int index = set;
            textures.add(new GuestProgram.RawTexture("_flw_coefficients" + set, GL30C.GL_TEXTURE_2D_ARRAY,
                    () -> targets.coefficients(index)));
        }
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int index = slot;
            textures.add(new GuestProgram.RawTexture("_flw_accumulate" + slot, GL11C.GL_TEXTURE_2D,
                    () -> targets.accumulate(index)));
        }
        BlendModeOverride blend = properties.blend(program);
        if (blend == null && shadow) {
            blend = BlendModeOverride.OFF;
        }
        BlendModeOverride finalBlend = blend;
        return publishDirect(programId, id -> new GuestProgram(id, label, renderPipeline.getBindGroupLayouts(), false,
                pipeline, shadow, forward == null ? packTarget(pipeline, drawBuffers, shadow)
                : _ -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, targets.forwardFbo()), finalBlend,
                bufferBlends(properties.bufferBlend(program), drawBuffers), AlphaTests.OFF, textures));
    }

    /**
     * Resolves remaining layers before the native front surface is shaded.
     */
    public static void resolveForwardOit(IrisRenderingPipeline pipeline, int texture) {
        var targets = oitTargets(pipeline, false);
        targets.forwardTarget(texture);
        int vertexArray = GlStateTracker.getVertexArray();
        GuestOitComposite.drawForward(targets.forwardSnapshot());
        // Iris binds its fullscreen vertex array once for the whole native composite sequence.
        GlStateTracker.bindVao(vertexArray);
    }

    /**
     * Publishes the nearest translucent metadata and current depth before native composites consume them.
     */
    public static void resolveForwardWater() {
        GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT
                | GL42C.GL_FRAMEBUFFER_BARRIER_BIT);
        int vertexArray = GlStateTracker.getVertexArray();
        GuestOitComposite.drawForwardWater(OIT_TARGETS[0].forwardCapture());
        GlStateTracker.bindVao(vertexArray);
    }

    private static GuestProgram forwardWater(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline) {
        var forward = ForwardOit.get(pipeline);
        String label = "flywheel:iris/forward_water";
        int programId = linkProgram(label, new GuestShaders.Stages(GuestOitCodegen.COMPOSITE_VERTEX, null, null, null,
                GuestOitCodegen.waterFragment(forward)));
        var targets = oitTargets(pipeline, false);
        var textures = new ArrayList<GuestProgram.RawTexture>();
        textures.add(new GuestProgram.RawTexture("_flw_depthRange", GL11C.GL_TEXTURE_2D, targets::depthRange));
        for (int slot = 0; slot < forward.waterBuffers().length; slot++) {
            int index = slot;
            textures.add(new GuestProgram.RawTexture("_flw_forwardWaterRead" + slot, GL11C.GL_TEXTURE_2D,
                    () -> targets.forwardWater(index)));
        }
        return publishDirect(programId, id -> new GuestProgram(id, label, renderPipeline.getBindGroupLayouts(), false,
                pipeline, false, packTarget(pipeline, forward.waterBuffers(), false), forward.waterBlend(),
                bufferBlends(forward.waterBufferBlends(), forward.waterBuffers()), AlphaTests.OFF, textures));
    }

    private static GuestProgram compositeDepth(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                               boolean shadow) {
        ProgramSource source = Objects.requireNonNull(oitSource(pipeline, shadow));
        String label = "flywheel:iris/" + source.getName() + "/oit_depth";
        int programId = linkProgram(label, new GuestShaders.Stages(GuestOitCodegen.COMPOSITE_VERTEX, null, null, null,
                GuestOitCodegen.depthFragment(shadow)));
        GuestOitTargets targets = oitTargets(pipeline, shadow);
        var forward = Objects.requireNonNull(oitProperties(pipeline, shadow)).oit(shadow).forward();
        return publishDirect(programId, id -> new GuestProgram(id, label, renderPipeline.getBindGroupLayouts(), false,
                pipeline, shadow, packTarget(pipeline, forward == null ? drawBuffers(source, shadow)
                : forward.resolveBuffers(), shadow), null, List.of(),
                AlphaTests.OFF,
                List.of(new GuestProgram.RawTexture("_flw_depthRange", GL11C.GL_TEXTURE_2D, targets::depthRange))));
    }

    private static List<GuestProgram.RawTexture> producerTextures(GuestOitTargets targets, OitPass pass) {
        if (pass == OitPass.DEPTH_RANGE) {
            return List.of();
        }
        List<GuestProgram.RawTexture> textures = new ArrayList<>();
        textures.add(new GuestProgram.RawTexture("_flw_depthRange", GL11C.GL_TEXTURE_2D, targets::depthRange));
        textures.add(new GuestProgram.RawTexture("_flw_blueNoise", GL11C.GL_TEXTURE_2D,
                () -> ((GlTexture) NoiseTextures.BLUE_NOISE.getTexture()).glId()));
        if (pass == OitPass.EVALUATE) {
            for (int set = 0; set < targets.ranks().length; set++) {
                int index = set;
                textures.add(new GuestProgram.RawTexture("_flw_coefficients" + set, GL30C.GL_TEXTURE_2D_ARRAY,
                        () -> targets.coefficients(index)));
            }
        }
        return textures;
    }

    private static List<GuestProgram.RawImage> producerImages(GuestOitTargets targets, OitPass pass) {
        if (pass != OitPass.EVALUATE) return List.of();
        var images = new ArrayList<GuestProgram.RawImage>();
        if (targets.forwardWaterCount() != 0)
            images.add(new GuestProgram.RawImage("_flw_forwardSurfaceClaim", InternalTextureFormat.R32UI,
                    targets::forwardSurfaceClaim));
        for (int slot = 0; slot < targets.forwardWaterCount(); slot++) {
            int index = slot;
            images.add(new GuestProgram.RawImage("_flw_forwardWater" + slot,
                    InternalTextureFormat.RGBA32UI,
                    () -> targets.forwardWater(index)));
        }
        return images;
    }

    private static GuestProgram.Target packTarget(IrisRenderingPipeline pipeline, int[] drawBuffers, boolean shadow) {
        return packTarget(pipeline, drawBuffers, shadow, false);
    }

    private static GuestProgram.Target packTarget(IrisRenderingPipeline pipeline, int[] drawBuffers, boolean shadow,
                                                  boolean snapshot) {
        if (shadow) {
            GlFramebuffer framebuffer = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shadowTargets()
                                                                                  .get()
                                                                                  .createShadowFramebuffer(
                                                                                          ImmutableSet.of(),
                                                                                          drawBuffers);
            return _ -> framebuffer.bind();
        }
        RenderTargets targets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets();
        GlFramebuffer beforeTranslucent = targets.createGbufferFramebuffer(pipeline.getFlippedAfterPrepare(),
                drawBuffers);
        GlFramebuffer afterTranslucent = targets.createGbufferFramebuffer(pipeline.getFlippedAfterTranslucent(),
                drawBuffers);
        if (!snapshot) return before -> (before ? beforeTranslucent : afterTranslucent).bind();
        return before -> {
            var framebuffer = before ? beforeTranslucent : afterTranslucent;
            framebuffer.addDepthAttachmentBypass(CAPTURE_DEPTH.texture());
            framebuffer.bind();
        };
    }

    private static List<BufferBlendOverride> bufferBlends(List<BufferBlendInformation> information,
                                                          int[] drawBuffers) {
        List<BufferBlendOverride> overrides = new ArrayList<>();
        for (BufferBlendInformation entry : information) {
            int index = Ints.indexOf(drawBuffers, entry.index());
            if (index > -1) {
                overrides.add(new BufferBlendOverride(index, entry.blendMode()));
            }
        }
        return overrides;
    }

    private static int linkProgram(String label, GuestShaders.Stages stages) {
        return linkProgram(label, stages, GuestShaders.ATTRIBUTES);
    }

    private static int linkProgram(String label, GuestShaders.Stages stages, String[] attributes) {
        if (Compilation.DUMP_SHADER_SOURCE) {
            dump(label, stages);
        }
        return linkStages(label, new int[]{GL20C.GL_VERTEX_SHADER, GL32C.GL_GEOMETRY_SHADER,
                        GL40C.GL_TESS_CONTROL_SHADER, GL40C.GL_TESS_EVALUATION_SHADER, GL20C.GL_FRAGMENT_SHADER},
                new String[]{stages.vertex(), stages.geometry(), stages.tessControl(), stages.tessEval(), stages.fragment()},
                attributes);
    }

    private static int linkBatch(GlCompilationBatch batch, String label, GuestShaders.Stages stages,
                                 String[] attributes, IntConsumer publish) {
        DriverStages driver = driverStages(label, stages);
        int[] shaders = new int[driver.types().length];
        for (int i = 0; i < shaders.length; i++) {
            shaders[i] = batch.sharedShader(driver.types()[i], driver.sources()[i], label);
        }
        return batch.link(label, shaders, attributes, publish);
    }

    private static DriverStages driverStages(String label, GuestShaders.Stages stages) {
        if (Compilation.DUMP_SHADER_SOURCE) {
            dump(label, stages);
        }
        int[] types = {GL20C.GL_VERTEX_SHADER, GL32C.GL_GEOMETRY_SHADER, GL40C.GL_TESS_CONTROL_SHADER,
                GL40C.GL_TESS_EVALUATION_SHADER, GL20C.GL_FRAGMENT_SHADER};
        String[] sources = {stages.vertex(), stages.geometry(), stages.tessControl(), stages.tessEval(), stages.fragment()};
        int[] presentTypes = new int[types.length];
        String[] presentSources = new String[types.length];
        int count = 0;
        for (int index = 0; index < types.length; index++) {
            if (sources[index] != null) {
                GuestShaders.requireSupportedStage(label, sources[index]);
                String source = sources[index];
                #if MC >= 26.3
                source = NativeShaderPreprocessor.preprocess(source, label, types[index], ShaderDefines.EMPTY);
                #endif
                presentTypes[count] = types[index];
                presentSources[count++] = source;
            }
        }
        return new DriverStages(Arrays.copyOf(presentTypes, count), Arrays.copyOf(presentSources, count));
    }

    private static int linkMeshProgram(String label, GuestTerrainMeshShaders.Stages stages) {
        if (Compilation.DUMP_SHADER_SOURCE) {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("flywheel_sources/iris");
            String stem = label.replaceAll("[^A-Za-z0-9._-]", "_");
            try {
                Files.createDirectories(dir);
                if (stages.task() != null) Files.writeString(dir.resolve(stem + ".task"), stages.task());
                else Files.deleteIfExists(dir.resolve(stem + ".task"));
                Files.writeString(dir.resolve(stem + ".mesh"), stages.mesh());
                Files.writeString(dir.resolve(stem + ".fsh"), stages.fragment());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return linkStages(label, new int[]{NVMeshShader.GL_TASK_SHADER_NV, NVMeshShader.GL_MESH_SHADER_NV,
                        GL20C.GL_FRAGMENT_SHADER}, new String[]{stages.task(), stages.mesh(), stages.fragment()},
                new String[0]);
    }

    private static int linkStages(String label, int[] types, String[] sources, String[] attributes) {
        int programId = GlStateManager.glCreateProgram();
        if (programId == 0) throw gpuFailure("Could not create program " + label, true);
        List<Integer> shaders = new ArrayList<>(5);
        boolean linked = false;
        try {
            for (int i = 0; i < types.length; i++) attach(programId, shaders, label, types[i], sources[i]);
            for (int location = 0; location < attributes.length; location++) {
                GlStateManager._glBindAttribLocation(programId, location, attributes[location]);
            }
            GlStateManager.glLinkProgram(programId);
            if (GlStateManager.glGetProgrami(programId, GL20C.GL_LINK_STATUS) == 0) {
                throw gpuFailure("Failed to link " + label + ": "
                        + GlStateManager.glGetProgramInfoLog(programId, 32768));
            }
            linked = true;
            return programId;
        } finally {
            for (int shader : shaders) {
                GL20C.glDetachShader(programId, shader);
                GlStateManager.glDeleteShader(shader);
            }
            if (!linked) {
                GlStateManager.glDeleteProgram(programId);
            }
        }
    }

    static void dump(String label, GuestShaders.Stages stages) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath()
                                                        .resolve("flywheel_sources/iris");
        String stem = label.substring(label.indexOf('/') + 1)
                           .replace('/', '_');
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(stem + ".vsh"), stages.vertex());
            Files.writeString(dir.resolve(stem + ".fsh"), stages.fragment());
            if (stages.geometry() != null) {
                Files.writeString(dir.resolve(stem + ".gsh"), stages.geometry());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void attach(int programId, List<Integer> shaders, String label, int glType,
                               @Nullable String source) {
        if (source == null) {
            return;
        }
        GuestShaders.requireSupportedStage(label, source);
        #if MC >= 26.3
        source = NativeShaderPreprocessor.preprocess(source, label, glType, ShaderDefines.EMPTY);
        #endif
        int shader = GlStateManager.glCreateShader(glType);
        if (shader == 0) throw gpuFailure("Could not create shader " + label, true);
        GlStateManager.glShaderSource(shader, source);
        GlStateManager.glCompileShader(shader);
        if (GlStateManager.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GlStateManager.glGetShaderInfoLog(shader, 32768);
            GlStateManager.glDeleteShader(shader);
            throw gpuFailure("Failed to compile " + label + " (" + glType + "): " + log);
        }
        GlStateManager.glAttachShader(programId, shader);
        shaders.add(shader);
    }

    private static BackendUnavailableException gpuFailure(String message) {
        return gpuFailure(message, false);
    }

    private static BackendUnavailableException gpuFailure(String message, boolean creation) {
        int error = GlStateManager._getError();
        if (error == GL11C.GL_OUT_OF_MEMORY) throw new OutOfMemoryError(message);
        if (error == GL45C.GL_CONTEXT_LOST) throw new IllegalStateException("OpenGL context lost: " + message);
        if (creation) throw new IllegalStateException(message + ": GL error 0x" + Integer.toHexString(error));
        return new BackendUnavailableException(message);
    }

    private record LinkKey(ProgramKey program, @Nullable ContractProgram contract, GuestShaders.Capture capture) {
    }

    private record ResolvedProgram(LinkKey key, ProgramSource source) {
    }

    private record PreparedProgram(String label, GuestShaders.Stages stages, IntFunction<GuestProgram> publish) {
    }

    private record DriverStages(int[] types, String[] sources) {
    }

    /**
     * Uber pipelines of one state across instance type generations.
     */
    private record VersionKey(PipelineKey key, boolean snapshot) {
    }

    private record PipelineKey(ProgramKey program, Transparency transparency, DepthTest depthTest, boolean depthWrite,
                               boolean colorWrite, boolean cull, boolean polygonOffset) {
        PipelineKey withTypeGen(int typeGen) {
            return new PipelineKey(program.withTypeGen(typeGen), transparency, depthTest, depthWrite, colorWrite, cull,
                    polygonOffset);
        }

        String cacheName() {
            return (program.cacheName() + "_" + transparency.name() + "_" + depthTest.name() + (depthWrite ? "_dw" : "")
                    + (colorWrite ? "" : "_nocw") + (cull ? "_cull" : "") + (polygonOffset ? "_po" : ""))
                    .toLowerCase(Locale.ROOT)
                    .replace('.', '_');
        }
    }
}
