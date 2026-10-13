package dev.engine_room.flywheel.iris.compile;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
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
import dev.engine_room.flywheel.backend.BackendRecovery;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.GlCompilationBatch;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import net.minecraft.client.renderer.ShaderDefines;
#endif
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.CrumblingPipelines;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.instancing.InstancingPipeline;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.iris.IrisBackends;
import dev.engine_room.flywheel.iris.engine.GuestVertexExtras;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.*;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
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

/**
 * Render-thread only; guest caches scoped to Iris pipeline ownership.
 */
public final class GuestPipelines {
    private static final Identifier GUEST_SHADER = ResourceUtil.rl("iris/guest");

    private static final Map<PipelineKey, RenderPipeline> PIPELINES = new HashMap<>();
    private static final Map<RenderPipeline, ProgramKey> PROGRAM_KEYS = new IdentityHashMap<>();

    private static final Map<RenderPipeline, Boolean> COMPOSITE_SHADOW = new IdentityHashMap<>();
    private static final RenderPipeline[] COMPOSITES = new RenderPipeline[2];
    private static final Map<RenderPipeline, Boolean> DEPTH_SHADOW = new IdentityHashMap<>();
    private static final RenderPipeline[] DEPTHS = new RenderPipeline[2];
    private static final BlendFunction MAX_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX,
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    private static final BlendFunction ADD_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE);
    private static final BlendFunction COMPOSITE_BLEND = new BlendFunction(BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final Map<LinkKey, GuestProgram> PROGRAMS = new HashMap<>();
    private static final Map<RenderPipeline, CompiledRenderPipeline> COMPILED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, CompiledRenderPipeline> MESH_COMPILED = new IdentityHashMap<>();
    private static final GuestOitTargets[] OIT_TARGETS = new GuestOitTargets[2];
    private static @Nullable IrisRenderingPipeline owner;
    private static @Nullable IrisRenderingPipeline warmed;
    private static @Nullable IrisRenderingPipeline glintOwner;
    private static boolean nativeGlint;

    private GuestPipelines() {
    }

    public static RenderPipeline instancing(PackRole role, Material material, InstanceType<?> type, boolean embedded) {
        PackRole routed = role.forMaterial(material);
        ProgramKey program = ProgramKey.of(routed, false, type, material, embedded, false, 0);
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(routed, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), embedded), k));
    }

    /**
     * Type-erased; {@code embedded} selects fragment variant.
     */
    public static RenderPipeline indirect(PackRole role, Material material, boolean embedded) {
        PackRole routed = role.forMaterial(material);
        ProgramKey program = ProgramKey.of(routed, true, null, material, embedded, false, InstanceTypeIds.snapshot()
                                                                                                         .types()
                                                                                                         .size());
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(routed, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), false), k));
    }

    /**
     * Colour-program positions; depth-only redraw.
     */
    public static RenderPipeline instancingDepthFill(PackRole role, Material material, InstanceType<?> type,
                                                     boolean embedded) {
        ProgramKey program = ProgramKey.of(role, false, type, material, embedded, false, 0);
        return PIPELINES.computeIfAbsent(depthFillKey(program, material),
                k -> register(role, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), true, false,
                        k.cull(), k.polygonOffset(), embedded), k));
    }

    public static RenderPipeline indirectDepthFill(PackRole role, Material material, boolean embedded) {
        ProgramKey program = ProgramKey.of(role, true, null, material, embedded, false, InstanceTypeIds.snapshot()
                                                                                                       .types()
                                                                                                       .size());
        return PIPELINES.computeIfAbsent(depthFillKey(program, material),
                k -> register(role, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), true, false,
                        k.cull(), k.polygonOffset(), false), k));
    }

    /**
     * OIT producer; {@code role} = TRANSLUCENT/SHADOW.
     */
    public static RenderPipeline instancingOit(PackRole role, OitPass pass, Material material, InstanceType<?> type,
                                               boolean embedded) {
        ProgramKey program = ProgramKey.of(role, false, type, material, embedded, false, 0)
                                       .withOit(pass);
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(role, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), embedded), k));
    }

    public static RenderPipeline indirectOit(PackRole role, OitPass pass, Material material, boolean embedded) {
        ProgramKey program = ProgramKey.of(role, true, null, material, embedded, false, InstanceTypeIds.snapshot()
                                                                                                       .types()
                                                                                                       .size())
                                       .withOit(pass);
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(role, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), false), k));
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
     * After composite; main reversed-Z, shadow forward-Z.
     */
    public static RenderPipeline oitDepth(boolean shadow) {
        int index = shadow ? 1 : 0;
        if (DEPTHS[index] == null) {
            DEPTHS[index] = RenderPipeline.builder()
                                          .withLocation(ResourceUtil.rl(
                                                  "pipeline/iris/oit_depth" + (shadow ? "_shadow" : "")))
                                          .withVertexShader(GUEST_SHADER)
                                          .withFragmentShader(GUEST_SHADER)
                                          .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                          .withDepthStencilState(new DepthStencilState(shadow
                                                  ? CompareOp.LESS_THAN_OR_EQUAL : CompareOp.GREATER_THAN_OR_EQUAL,
                                                  true, 0.0f, 0.0f))
                                          .withCull(false)
                                          .withColorTargetState(new ColorTargetState(Optional.empty(),
                                                  GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                                          .build();
            DEPTH_SHADOW.put(DEPTHS[index], shadow);
        }
        return DEPTHS[index];
    }

    private static PipelineKey materialKey(ProgramKey program, Material material) {
        boolean shadow = program.role().shadow;
        boolean depthWrite = Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline
                ? GuestProgramResolver.depthWrite(pipeline, program, material.writeMask().depth())
                : shadow || program.role() == PackRole.ENTITIES || material.writeMask().depth();
        return new PipelineKey(program, material.transparency(), material.depthTest(), depthWrite,
                material.writeMask()
                        .color(), !shadow && material.backfaceCulling(), !shadow && material.polygonOffset());
    }

    private static PipelineKey depthFillKey(ProgramKey program, Material material) {
        return new PipelineKey(program, material.transparency(), material.depthTest(), true, false,
                material.backfaceCulling(), material.polygonOffset());
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

    public static RenderPipeline crumbling(Material crumblingMaterial, InstanceType<?> type, boolean indirect) {
        ProgramKey program = ProgramKey.of(PackRole.DAMAGED, indirect, type, crumblingMaterial, false, true, 0);
        PipelineKey key = new PipelineKey(program, crumblingMaterial.transparency(), crumblingMaterial.depthTest(),
                false, true, crumblingMaterial.backfaceCulling(), false);
        return PIPELINES.computeIfAbsent(key,
                k -> register(PackRole.DAMAGED, CrumblingPipelines.stateBuilder(k.depthTest(), k.cull(), indirect),
                        k));
    }

    private static RenderPipeline register(PackRole role, RenderPipeline.Builder builder, PipelineKey key) {
        if (role.shadow) {
            // Shadow state is forward-Z (cleared to 1); registration compensates Iris's native bind policy.
            builder.withDepthStencilState(new DepthStencilState(forwardZ(key.depthTest()), true, 0.0f, 0.0f))
                   .withCull(false);
        }
        OitPass oit = key.program()
                         .oit();
        if (oit != null) {
            boolean offset = key.polygonOffset();
            builder.withDepthStencilState(new DepthStencilState(role.shadow ? forwardZ(key.depthTest())
                           : key.depthTest().compareOp, false, offset ? 1.0f : 0.0f, offset ? 10.0f : 0.0f))
                   .withColorTargetState(new ColorTargetState(
                           Optional.of(oit == OitPass.DEPTH_RANGE ? MAX_BLEND : ADD_BLEND), GpuFormat.RGBA8_UNORM,
                           ColorTargetState.WRITE_ALL));
        }
        RenderPipeline pipeline = builder.withVertexBinding(1, GuestVertexExtras.FORMAT)
                                         .withLocation(ResourceUtil.rl("pipeline/iris/" + key.cacheName()))
                                         .withVertexShader(GUEST_SHADER)
                                         .withFragmentShader(GUEST_SHADER)
                                         .build();
        PROGRAM_KEYS.put(pipeline, key.program());
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
        TerrainPipelines.Kind terrainKind = GuestTerrainGate.enabled() && Iris.isPackInUseQuick()
                ? TerrainPipelines.terrainKind(pipeline) : null;
        if (key == null && compositeShadow == null && depthShadow == null && terrainKind == null) {
            return null;
        }
        if (current == null) current = currentPipeline();
        track(current);
        boolean mesh = terrainKind != null && GuestTerrainGate.meshActive;
        Map<RenderPipeline, CompiledRenderPipeline> cache = mesh ? MESH_COMPILED : COMPILED;
        CompiledRenderPipeline compiled = cache.get(pipeline);
        if (compiled == null) {
            GuestProgram program = key != null ? program(current, pipeline, key)
                    : terrainKind != null ? terrain(current, pipeline, terrainKind, mesh)
                    : compositeShadow != null ? composite(current, pipeline, compositeShadow)
                    : compositeDepth(current, pipeline, depthShadow);
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
        if (!program.shadow() || state == null) return state;
        // Compat with Iris 1.11.7: it inverts every native depth compare during shadow rendering. Guests
        // already provide forward-Z state, so compensate here before that bind-time inversion, including
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
        boolean shadows = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shadowTargets().get() != null;
        long start = System.nanoTime();
        track(pipeline);
        int before = PROGRAMS.size();
        Map<LinkKey, List<RenderPipeline>> pending = new HashMap<>();
        Set<RenderPipeline> requested = new ReferenceOpenHashSet<>();
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            batch.splash(0);
            for (InstanceType<?> type : types) {
                for (Material material : materials) {
                    for (PackRole role : PackRole.values()) {
                        if (role == PackRole.DAMAGED || role == PackRole.GLINT) continue;
                        if (role.shadow && (!shadows || emissive(material))) continue;
                        for (boolean embedded : new boolean[]{false, true}) {
                            RenderPipeline draw = indirect ? indirect(role, material, embedded)
                                    : instancing(role, material, type, embedded);
                            ProgramKey key = PROGRAM_KEYS.get(draw);
                            if (GuestProgramResolver.contractSource(pipeline, key) == null
                                    && ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver().resolveNullable(
                                    key.role().programId) == null)
                                continue;
                            prepare(draw, pipeline, batch, pending, requested);
                            if (role != PackRole.TRANSLUCENT && role != PackRole.SHADOW) continue;
                            boolean oit = OitTransparency.orderIndependent(material) && !OitTransparency.additive(
                                    material)
                                    && oitActive(pipeline, role.shadow);
                            if (!oit) continue;
                            prepare(indirect ? indirectDepthFill(role, material, embedded)
                                            : instancingDepthFill(role, material, type, embedded), pipeline, batch, pending,
                                    requested);
                            for (OitPass pass : OitPass.values()) {
                                prepare(indirect ? indirectOit(role, pass, material, embedded)
                                                : instancingOit(role, pass, material, type, embedded), pipeline, batch, pending,
                                        requested);
                            }
                        }
                    }
                }
            }
            batch.finish(() -> {
            });
        }
        FlwBackend.LOGGER.info("guest pipeline warm-up took {}ms ({} new programs, {} material variants)",
                (System.nanoTime() - start) / 1_000_000, PROGRAMS.size() - before, materials.size());
        warmed = pipeline;
    }

    private static void prepare(RenderPipeline renderPipeline, IrisRenderingPipeline pipeline, GlCompilationBatch batch,
                                Map<LinkKey, List<RenderPipeline>> pending, Set<RenderPipeline> requested) {
        if (!requested.add(renderPipeline)) return;
        ResolvedProgram resolved = resolveProgram(pipeline, PROGRAM_KEYS.get(renderPipeline));
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
        PreparedProgram prepared = prepareLink(pipeline, renderPipeline, resolved.key().program(),
                resolved.key().contract(), resolved.source());
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
                                        TerrainPipelines.Kind kind, boolean mesh) {
        boolean cutout = kind.cutout();
        boolean shadow = kind.shadow();
        int oitPass = kind.oitPass();
        ProgramId programId = oitPass >= 0 ? ProgramId.Water
                : shadow ? (cutout ? ProgramId.ShadowCutout : ProgramId.ShadowSolid)
                : (cutout ? ProgramId.TerrainCutout : ProgramId.TerrainSolid);
        ProgramSource source = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()
                                                                         .resolveNullable(programId);
        if (source == null) {
            throw new BackendUnavailableException("Shaderpack has no program for " + programId);
        }
        AlphaTest alpha = source.getDirectives()
                                .getAlphaTestOverride()
                                .orElse(oitPass >= 0 ? AlphaTests.ONE_TENTH_ALPHA
                                        : cutout ? AlphaTests.HALF_ALPHA : AlphaTests.OFF);
        int[] drawBuffers = drawBuffers(source, shadow);
        if (oitPass >= 0 && oitPass < 3) {
            return terrainOit(pipeline, renderPipeline, source, alpha, drawBuffers, oitPass, mesh);
        }
        String label = "flywheel:iris/" + source.getName() + (oitPass == 3 ? "/terrain_capture" : shadow ? "/shadow_terrain" : "/terrain")
                + (cutout ? "_cutout" : "");
        int programId2;
        int meshQuads = 0;
        int taskQuads = 0;
        boolean taskRecovery = false;
        boolean compactSafe = false;
        if (mesh) {
            GuestTerrainMeshShaders.Stages stages = GuestTerrainMeshShaders.build(pipeline, source, alpha, shadow,
                    drawBuffers, null);
            programId2 = linkMeshProgram(label, stages);
            meshQuads = stages.quads();
            taskQuads = stages.taskQuads();
            taskRecovery = stages.taskRecovery();
            compactSafe = stages.compactSafe();
        } else {
            GuestTerrainShaders.Stages stages = GuestTerrainShaders.build(pipeline, source, alpha, shadow);
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
                                           boolean mesh) {
        ContractProperties properties = Objects.requireNonNull(oitProperties(pipeline, false));
        OitPass pass = OitPass.values()[oitPass];
        int[] oitBuffers = drawBuffers(Objects.requireNonNull(oitSource(pipeline, false)), false);
        GuestShaders.OitSpec spec = new GuestShaders.OitSpec(pass, properties.oit(false), oitBuffers, false);
        String label = "flywheel:iris/" + source.getName() + "/terrain_oit_" + pass.name().toLowerCase(Locale.ROOT);
        int programId;
        int meshQuads = 0;
        int taskQuads = 0;
        boolean taskRecovery = false;
        if (mesh) {
            GuestTerrainMeshShaders.Stages stages = GuestTerrainMeshShaders.build(pipeline, source, alpha, false,
                    drawBuffers, spec);
            programId = linkMeshProgram(label, stages);
            meshQuads = stages.quads();
            taskQuads = stages.taskQuads();
            taskRecovery = stages.taskRecovery();
        } else {
            GuestTerrainShaders.Stages stages = GuestTerrainShaders.buildOit(pipeline, source, alpha, drawBuffers,
                    spec);
            programId = linkProgram(label,
                    new GuestShaders.Stages(stages.vertex(), stages.geometry(), null, null, stages.fragment()),
                    terrainAttributes());
        }

        GuestOitTargets targets = oitTargets(pipeline, false);
        GuestProgram program = publishDirect(programId, id -> new GuestProgram(id, label,
                renderPipeline.getBindGroupLayouts(), false, pipeline, false,
                before -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                        oitPass == 0 ? targets.depthRangeFbo()
                                : oitPass == 1 ? targets.coefficientsFbo() : targets.accumulateFbo()),
                null, List.of(), alpha, meshTextures(mesh, producerTextures(targets, pass))));
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
        if (owner == pipeline) {
            track(null);
        }
    }

    private static void track(@Nullable IrisRenderingPipeline current) {
        if (current == owner) {
            return;
        }
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
        for (int i = 0; i < OIT_TARGETS.length; i++) {
            if (OIT_TARGETS[i] != null) {
                OIT_TARGETS[i].delete();
                OIT_TARGETS[i] = null;
            }
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

    public static boolean deferredOitActive(IrisRenderingPipeline pipeline) {
        var profile = contractSet(pipeline).flywheel$deferredOit();
        return profile != null && profile.capturesTerrain();
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
            OIT_TARGETS[index] = new GuestOitTargets(oit.ranks(), formats);
        }
        return OIT_TARGETS[index];
    }

    private static @Nullable ProgramSource oitSource(IrisRenderingPipeline pipeline, boolean shadow) {
        return contractSet(pipeline).flywheel$contractSource(
                shadow ? ContractProgram.SHADOW_TRANSLUCENT : ContractProgram.GBUFFERS_TRANSLUCENT);
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
        ResolvedProgram resolved = resolveProgram(pipeline, key);
        return PROGRAMS.computeIfAbsent(resolved.key(),
                k -> link(pipeline, renderPipeline, k.program(), k.contract(), resolved.source()));
    }

    private static ResolvedProgram resolveProgram(IrisRenderingPipeline pipeline, ProgramKey key) {
        var resolved = GuestProgramResolver.resolve(pipeline, key);
        return new ResolvedProgram(new LinkKey(resolved.program(), resolved.contract()), resolved.source());
    }

    private static GuestProgram link(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline, ProgramKey key,
                                     @Nullable ContractProgram contract, ProgramSource source) {
        PreparedProgram prepared = prepareLink(pipeline, renderPipeline, key, contract, source);
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
                                               ProgramKey key,
                                               @Nullable ContractProgram contract, ProgramSource source) {
        var state = GuestProgramResolver.state(pipeline, key, contract, source);
        AlphaTest alpha = state.alpha();
        boolean shadow = key.role().shadow;
        int[] drawBuffers = drawBuffers(source, shadow);
        OitPass pass = key.oit();
        GuestShaders.OitSpec oit = pass == null ? null : new GuestShaders.OitSpec(pass,
                Objects.requireNonNull(oitProperties(pipeline, shadow)).oit(shadow), drawBuffers, shadow);
        GuestShaders.Stages stages = GuestShaders.build(pipeline, source, alpha, key, contract != null, oit);

        String label = "flywheel:iris/" + source.getName() + "/" + key.cacheName();
        AlphaTest finalAlpha = alpha;

        if (oit != null) {
            GuestOitTargets targets = oitTargets(pipeline, shadow);
            int fboIndex = pass.ordinal();
            return new PreparedProgram(label, stages, programId -> new GuestProgram(programId, label,
                    renderPipeline.getBindGroupLayouts(), false, pipeline, shadow,
                    before -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                            fboIndex == 0 ? targets.depthRangeFbo()
                                    : fboIndex == 1 ? targets.coefficientsFbo() : targets.accumulateFbo()), null,
                    List.of(), finalAlpha, producerTextures(targets, pass)));
        }

        List<BufferBlendInformation> bufferBlendInformation = state.bufferBlends();
        BlendModeOverride blend = state.blend();
        BlendModeOverride finalBlend = blend;
        boolean grayscaleFont = state.grayscale();
        return new PreparedProgram(label, stages, programId -> {
            GuestProgram program = new GuestProgram(programId, label,
                    renderPipeline.getBindGroupLayouts(), key.crumbling(), pipeline,
                    shadow, packTarget(pipeline, drawBuffers, shadow), finalBlend,
                    bufferBlends(bufferBlendInformation, drawBuffers), finalAlpha, List.of());
            if (grayscaleFont) program.useGrayscaleAlbedo();
            return program;
        });
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
                pipeline, shadow, packTarget(pipeline, drawBuffers, shadow), finalBlend,
                bufferBlends(properties.bufferBlend(program), drawBuffers), AlphaTests.OFF, textures));
    }

    private static GuestProgram compositeDepth(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                               boolean shadow) {
        ProgramSource source = Objects.requireNonNull(oitSource(pipeline, shadow));
        String label = "flywheel:iris/" + source.getName() + "/oit_depth";
        int programId = linkProgram(label, new GuestShaders.Stages(GuestOitCodegen.COMPOSITE_VERTEX, null, null, null,
                GuestOitCodegen.depthFragment(shadow)));
        GuestOitTargets targets = oitTargets(pipeline, shadow);
        return publishDirect(programId, id -> new GuestProgram(id, label, renderPipeline.getBindGroupLayouts(), false,
                pipeline, shadow, packTarget(pipeline, drawBuffers(source, shadow), shadow), null, List.of(),
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

    private static GuestProgram.Target packTarget(IrisRenderingPipeline pipeline, int[] drawBuffers, boolean shadow) {
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
        return before -> (before ? beforeTranslucent : afterTranslucent).bind();
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
        if (Compilation.DUMP_SHADER_SOURCE) {
            dump(label, stages);
        }
        int[] types = {GL20C.GL_VERTEX_SHADER, GL32C.GL_GEOMETRY_SHADER, GL40C.GL_TESS_CONTROL_SHADER,
                GL40C.GL_TESS_EVALUATION_SHADER, GL20C.GL_FRAGMENT_SHADER};
        String[] sources = {stages.vertex(), stages.geometry(), stages.tessControl(), stages.tessEval(), stages.fragment()};
        int[] shaders = new int[5];
        int count = 0;
        for (int index = 0; index < types.length; index++) {
            if (sources[index] != null) {
                GuestShaders.requireSupportedStage(label, sources[index]);
                String source = sources[index];
                #if MC >= 26.3
                source = NativeShaderPreprocessor.preprocess(source, label, types[index], ShaderDefines.EMPTY);
                #endif
                shaders[count++] = batch.sharedShader(types[index], source, label);
            }
        }
        return batch.link(label, Arrays.copyOf(shaders, count), attributes, publish);
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

    private record LinkKey(ProgramKey program, @Nullable ContractProgram contract) {
    }

    private record ResolvedProgram(LinkKey key, ProgramSource source) {
    }

    private record PreparedProgram(String label, GuestShaders.Stages stages, IntFunction<GuestProgram> publish) {
    }

    private record PipelineKey(ProgramKey program, Transparency transparency, DepthTest depthTest, boolean depthWrite,
                               boolean colorWrite, boolean cull, boolean polygonOffset) {
        String cacheName() {
            return (program.cacheName() + "_" + transparency.name() + "_" + depthTest.name() + (depthWrite ? "_dw" : "")
                    + (colorWrite ? "" : "_nocw") + (cull ? "_cull" : "") + (polygonOffset ? "_po" : ""))
                    .toLowerCase(Locale.ROOT)
                    .replace('.', '_');
        }
    }
}
