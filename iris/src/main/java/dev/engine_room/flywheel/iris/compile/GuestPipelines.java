package dev.engine_room.flywheel.iris.compile;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
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
import dev.engine_room.flywheel.backend.compile.GlCompilationBatch;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
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
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.iris.IrisBackends;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.SundialTranslucent;
import dev.engine_room.flywheel.iris.engine.GuestVertexExtras;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.*;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
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
import java.util.regex.Pattern;

/** Render-thread only; guest caches scoped to Iris pipeline ownership. */
public final class GuestPipelines {
    private static final Identifier GUEST_SHADER = ResourceUtil.rl("iris/guest");
    private static final Identifier GRAYSCALE_FONT = ResourceUtil.rl("material/nametag.frag");
    private static final Pattern MC_ENTITY = Pattern.compile("\\bmc_Entity\\b");

    private static final Map<PipelineKey, RenderPipeline> PIPELINES = new HashMap<>();
    private static final Map<RenderPipeline, ProgramKey> PROGRAM_KEYS = new IdentityHashMap<>();

    private static final Map<RenderPipeline, Boolean> COMPOSITE_SHADOW = new IdentityHashMap<>();
    private static final RenderPipeline[] COMPOSITES = new RenderPipeline[2];
    private static final Map<RenderPipeline, Boolean> DEPTH_SHADOW = new IdentityHashMap<>();
    private static final RenderPipeline[] DEPTHS = new RenderPipeline[2];
    private static final BlendFunction MAX_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX,
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    private static final BlendFunction ADD_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE);
    private static final BlendModeOverride ENTITY_TRANSLUCENT_BLEND = new BlendModeOverride(new BlendMode(
            BlendModeFunction.SRC_ALPHA.getGlId(), BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId(),
            BlendModeFunction.ONE.getGlId(), BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId()));
    private static final BlendModeOverride GLINT_BLEND = new BlendModeOverride(new BlendMode(
            GL11C.GL_SRC_COLOR, GL11C.GL_ONE, GL11C.GL_ZERO, GL11C.GL_ONE));
    private static final BlendFunction COMPOSITE_BLEND = new BlendFunction(BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final Map<LinkKey, GuestProgram> PROGRAMS = new HashMap<>();
    private static final Map<RenderPipeline, GlRenderPipeline> COMPILED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, GlRenderPipeline> MESH_COMPILED = new IdentityHashMap<>();
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

    /** Type-erased; {@code embedded} selects fragment variant. */
    public static RenderPipeline indirect(PackRole role, Material material, boolean embedded) {
        PackRole routed = role.forMaterial(material);
        ProgramKey program = ProgramKey.of(routed, true, null, material, embedded, false, InstanceTypeIds.snapshot()
                                                                                                         .types()
                                                                                                         .size());
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(routed, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), false), k));
    }

    /** Colour-program positions; depth-only redraw. */
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

    /** OIT producer; {@code role} = TRANSLUCENT/SHADOW. */
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

    /** Contract translucent framebuffer; after OIT producer passes. */
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

    /** After composite; main reversed-Z, shadow forward-Z. */
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
        PackRole role = program.role();
        boolean gbuffer = (role == PackRole.ADDITIVE || role.blockRole() == PackRole.TRANSLUCENT)
                && Iris.getPipelineManager()
                       .getPipelineNullable() instanceof IrisRenderingPipeline pipeline
                && (role == PackRole.ADDITIVE ? deferredEmissive(pipeline) : deferredTranslucent(pipeline)
                && !forwardUnlit(contractSet((IrisRenderingPipelineAccessor) pipeline), program));
        return new PipelineKey(program, material.transparency(), material.depthTest(), shadow || gbuffer
                || role == PackRole.ENTITIES || material.writeMask()
                                                        .depth(),
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

    /** Guest pipeline or {@code null}; current Iris pipeline ownership. */
    public static @Nullable GlRenderPipeline compiled(RenderPipeline pipeline) {
        return compiled(pipeline, null);
    }

    private static @Nullable GlRenderPipeline compiled(RenderPipeline pipeline,
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
        Map<RenderPipeline, GlRenderPipeline> cache = mesh ? MESH_COMPILED : COMPILED;
        GlRenderPipeline compiled = cache.get(pipeline);
        if (compiled == null) {
            GuestProgram program = key != null ? program(current, pipeline, key)
                    : terrainKind != null ? terrain(current, pipeline, terrainKind, mesh)
                    : compositeShadow != null ? composite(current, pipeline, compositeShadow)
                    : compositeDepth(current, pipeline, depthShadow);
            compiled = new GlRenderPipeline(pipeline, program);
            cache.put(pipeline, compiled);
        }
        return compiled;
    }

    /** Resource apply/dimension entry; before visual draws. */
    public static void warmUp(IrisRenderingPipeline pipeline) {
        if (warmed == pipeline) return;
        if (glintOwner != pipeline) {
            glintOwner = pipeline;
            ContractProgramSet contracts = contractSet((IrisRenderingPipelineAccessor) pipeline);
            boolean nextNativeGlint = !contracts.flywheel$hasContract(ContractProgram.GBUFFERS_GLINT);
            if (!nextNativeGlint) {
                ProgramSource glint = contracts.flywheel$contractSource(ContractProgram.GBUFFERS_GLINT);
                nextNativeGlint = glint.getTessControlSource().isPresent() || glint.getTessEvalSource().isPresent();
            }
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
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        boolean shadows = accessor.flywheel$shadowTargets().get() != null;
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
                            if (contractSource(accessor, key) == null
                                    && accessor.flywheel$resolver().resolveNullable(key.role().programId) == null)
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
            COMPILED.put(renderPipeline, new GlRenderPipeline(renderPipeline, cached));
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
            for (RenderPipeline output : outputs) COMPILED.put(output, new GlRenderPipeline(output, program));
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
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        boolean cutout = kind.cutout();
        boolean shadow = kind.shadow();
        int oitPass = kind.oitPass();
        ProgramId programId = oitPass >= 0 ? ProgramId.Water
                : shadow ? (cutout ? ProgramId.ShadowCutout : ProgramId.ShadowSolid)
                : (cutout ? ProgramId.TerrainCutout : ProgramId.TerrainSolid);
        ProgramSource source = accessor.flywheel$resolver()
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
                packTarget(accessor, pipeline, drawBuffers, shadow), source.getDirectives()
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
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        ContractProperties properties = Objects.requireNonNull(oitProperties(pipeline, false));
        OitPass pass = OitPass.values()[oitPass];
        int[] oitBuffers = drawBuffers(Objects.requireNonNull(oitSource(accessor, false)), false);
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

        GuestOitTargets targets = oitTargets(accessor, false);
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

    /** Iris pipeline destruction, render thread; releases owned guest resources. */
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
        COMPILED.values()
                .stream()
                .filter(compiled -> !PROGRAM_KEYS.containsKey(compiled.info()))
                .forEach(compiled -> compiled.program()
                                             .close());
        COMPILED.clear();
        MESH_COMPILED.values().forEach(compiled -> compiled.program().close());
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

    /** Render-thread draw partitioning; selected when the active Iris pipeline is installed. */
    public static boolean nativeGlint() {
        return nativeGlint;
    }

    /** Order-independent draws admitted to contract OIT. */
    public static boolean oitActive(IrisRenderingPipeline pipeline, boolean shadow) {
        if (!GuestOitTargets.SUPPORTED) {
            return false;
        }
        ContractProperties properties = oitProperties(pipeline, shadow);
        if (properties == null || !properties.oit(shadow)
                                             .enabled()) {
            return false;
        }
        ProgramSource source = oitSource((IrisRenderingPipelineAccessor) pipeline, shadow);
        return source != null && !hasExtraStages(source);
    }

    public static boolean deferredOitActive(IrisRenderingPipeline pipeline) {
        var profile = contractSet((IrisRenderingPipelineAccessor) pipeline).flywheel$deferredOit();
        return profile != null && profile.capturesTerrain();
    }

    /** Before producers; prepare targets. Returns coefficient-set presence. */
    public static boolean prepareOit(IrisRenderingPipeline pipeline, boolean shadow) {
        track(pipeline);
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        GuestOitTargets targets = oitTargets(accessor, shadow);
        if (shadow) {
            ShadowRenderTargets shadowTargets = accessor.flywheel$shadowTargets()
                                                        .get();
            targets.prepare(shadowTargets.getDepthTexture(), shadowTargets.getResolution(),
                    shadowTargets.getResolution());
        } else {
            RenderTargets renderTargets = accessor.flywheel$renderTargets();
            targets.prepare(renderTargets.getDepthTexture(), renderTargets.getCurrentWidth(),
                    renderTargets.getCurrentHeight());
        }
        return targets.ranks().length > 0;
    }

    private static GuestOitTargets oitTargets(IrisRenderingPipelineAccessor accessor, boolean shadow) {
        int index = shadow ? 1 : 0;
        if (OIT_TARGETS[index] == null) {
            ContractProperties.Oit oit = Objects.requireNonNull(oitProperties((IrisRenderingPipeline) accessor, shadow))
                                                .oit(shadow);
            int[] drawBuffers = drawBuffers(Objects.requireNonNull(oitSource(accessor, shadow)), shadow);
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

    private static @Nullable ProgramSource oitSource(IrisRenderingPipelineAccessor accessor, boolean shadow) {
        return contractSet(accessor).flywheel$contractSource(
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

    /** Emissive guests: opaque G-buffer before deferred passes. */
    public static boolean deferredEmissive(IrisRenderingPipeline pipeline) {
        return ((ContractShaderPack) ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack())
                .flywheel$deferredEmissive();
    }

    /** Emissive guests: composite light buffer, no own depth. */
    public static boolean emissiveLight(IrisRenderingPipeline pipeline) {
        return ((ContractShaderPack) ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack())
                .flywheel$emissiveLight();
    }

    /** Translucent guests: depth required by pack composite. */
    public static boolean deferredTranslucent(IrisRenderingPipeline pipeline) {
        return ((ContractShaderPack) ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack())
                .flywheel$deferredTranslucent();
    }

    /** Authored Colorwheel contract properties; absent = {@code null}. */
    public static @Nullable ContractProperties contractProperties(IrisRenderingPipeline pipeline) {
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        return contractSet(accessor).flywheel$contractSource(ContractProgram.GBUFFERS) == null ? null
                : ((ContractShaderPack) accessor.flywheel$pack()).flywheel$contractProperties();
    }

    private static @Nullable ContractProperties oitProperties(IrisRenderingPipeline pipeline, boolean shadow) {
        ContractProperties authored = ((ContractShaderPack) ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack())
                .flywheel$contractProperties();
        if (authored.oit(shadow).explicitlyDisabled()) return authored;
        if (!shadow) {
            ContractProperties nativeOit = contractSet((IrisRenderingPipelineAccessor) pipeline).flywheel$nativeOit();
            if (nativeOit != null) return nativeOit;
        }
        return contractProperties(pipeline);
    }

    private static ContractProgramSet contractSet(IrisRenderingPipelineAccessor accessor) {
        return (ContractProgramSet) ((ProgramFallbackResolverAccessor) accessor.flywheel$resolver()).flywheel$programs();
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
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        ProgramSource contract = contractSource(accessor, key);
        if (contract == null) {
            if (key.oit() != null) {
                throw new IllegalStateException("OIT producer without a contract translucent program");
            }
            return new ResolvedProgram(new LinkKey(key.forNative(), null), nativeSource(accessor, key.role()));
        }
        ContractProgram id = Objects.requireNonNull(ContractProgram.byName(contract.getName()));
        return new ResolvedProgram(new LinkKey(key.forContract(), id), contract);
    }

    private static @Nullable ProgramSource contractSource(IrisRenderingPipelineAccessor accessor, ProgramKey key) {
        ContractProgramSet contractSet = contractSet(accessor);
        ContractProgram wanted = forwardUnlit(contractSet, key) ? ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT
                : ContractProgram.of(key.role(), Objects.requireNonNull(key.transparency()),
                contractSet::flywheel$hasContract);
        ProgramSource contract = key.role() == PackRole.EYES || key.role() == PackRole.ENTITIES_TRANSLUCENT
                || key.role() == PackRole.ENTITIES
                || (key.role() == PackRole.ADDITIVE || key.role() == PackRole.GLINT)
                && !contractSet.flywheel$hasContract(wanted) ? null : contractSet.flywheel$contractSource(wanted);
        if (contract != null && (contract.getTessControlSource()
                                         .isPresent() || contract.getTessEvalSource()
                                                                 .isPresent())) {
            FlwBackend.LOGGER.warn("{} has tessellation stages; drawing through the pack's own programs",
                    contract.getName());
            contract = null;
        }
        if (contract != null && key.role() == PackRole.TERRAIN && !readsMcEntity(contract)) {
            FlwBackend.LOGGER.info("{} ignores mc_Entity; borrowed block draws take the pack's terrain program",
                    contract.getName());
            contract = null;
        }
        return contract;
    }

    private static boolean forwardUnlit(ContractProgramSet contracts, ProgramKey key) {
        return key.unlit() && key.role().blockRole() == PackRole.TRANSLUCENT
                && FogShaders.NONE.source().equals(key.fog())
                && contracts.flywheel$hasContract(ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT);
    }

    private static boolean readsMcEntity(ProgramSource source) {
        return source.getVertexSource()
                     .map(vertex -> MC_ENTITY.matcher(vertex)
                                             .find())
                     .orElse(false);
    }

    private static ProgramSource nativeSource(IrisRenderingPipelineAccessor accessor, PackRole role) {
        if (role == PackRole.ADDITIVE) {
            ProgramSource beam = resolveExactly(accessor, ProgramId.BeaconBeam);
            if (beam != null && (((ContractShaderPack) accessor.flywheel$pack()).flywheel$deferredEmissive()
                    || writesColour(beam))) {
                return beam;
            }
            ProgramSource lightning = resolveExactly(accessor, ProgramId.Lightning);
            if (lightning != null) {
                return lightning;
            }
            if (beam != null) {
                return beam;
            }
        }
        ProgramSource source = accessor.flywheel$resolver()
                                       .resolveNullable(role.programId);
        if (source == null) {
            throw new BackendUnavailableException("Shaderpack has no program for " + role.programId);
        }
        return source;
    }

    private static @Nullable ProgramSource resolveExactly(IrisRenderingPipelineAccessor accessor, ProgramId id) {
        ProgramSource source = accessor.flywheel$resolver()
                                       .resolveNullable(id);
        return source != null && source.getName()
                                       .equals(id.getSourceName()) ? source : null;
    }

    private static boolean writesColour(ProgramSource source) {
        for (int buffer : source.getDirectives()
                                .getDrawBuffers()) {
            if (buffer == 0) {
                return true;
            }
        }
        return false;
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
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        AlphaTest alpha = contract != null ? AlphaTests.OFF : source.getDirectives()
                                                                    .getAlphaTestOverride()
                                                                    .orElse(key.role() == PackRole.GLINT
                                                                            ? ShaderKey.GLINT.getAlphaTest() : key.alphaTest());
        boolean nativeAdapter = source.getFragmentSource().orElseThrow().contains(ContractPatches.NATIVE_TRANSLUCENT);
        boolean nativeShadowAdapter = source.getFragmentSource().orElseThrow()
                                            .contains(ContractPatches.NATIVE_SHADOW_TRANSLUCENT);
        if (contract != null && source.getFragmentSource().orElseThrow().contains(SundialTranslucent.MARKER)) {
            alpha = AlphaTests.OFF;
        }
        if (contract != null && nativeAdapter) {
            alpha = accessor.flywheel$resolver().resolveNullable(ProgramId.Water).getDirectives().getAlphaTestOverride()
                            .orElse(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT.getAlphaTest());
        }
        if (contract != null && (nativeShadowAdapter || source.getFragmentSource().orElseThrow()
                                                              .contains(SundialTranslucent.SHADOW_MARKER))) {
            alpha = accessor.flywheel$resolver().resolveNullable(ProgramId.ShadowWater).getDirectives()
                            .getAlphaTestOverride()
                            .orElse(ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT.getAlphaTest());
        }
        boolean shadow = key.role().shadow;
        int[] drawBuffers = drawBuffers(source, shadow);
        OitPass pass = key.oit();
        GuestShaders.OitSpec oit = pass == null ? null : new GuestShaders.OitSpec(pass,
                Objects.requireNonNull(oitProperties(pipeline, shadow)).oit(shadow), drawBuffers, shadow);
        GuestShaders.Stages stages = GuestShaders.build(pipeline, source, alpha, key, contract != null, oit);

        String label = "flywheel:iris/" + source.getName() + "/" + key.cacheName();
        AlphaTest finalAlpha = alpha;

        if (oit != null) {
            GuestOitTargets targets = oitTargets(accessor, shadow);
            int fboIndex = pass.ordinal();
            return new PreparedProgram(label, stages, programId -> new GuestProgram(programId, label,
                    renderPipeline.getBindGroupLayouts(), false, pipeline, shadow,
                    before -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                            fboIndex == 0 ? targets.depthRangeFbo()
                                    : fboIndex == 1 ? targets.coefficientsFbo() : targets.accumulateFbo()), null,
                    List.of(), finalAlpha, producerTextures(targets, pass)));
        }

        List<BufferBlendInformation> bufferBlendInformation;
        BlendModeOverride blend;
        if (nativeAdapter || nativeShadowAdapter) {
            ProgramSource nativeWater = accessor.flywheel$resolver().resolveNullable(
                    nativeShadowAdapter ? ProgramId.ShadowWater : ProgramId.Water);
            bufferBlendInformation = nativeWater.getDirectives().getBufferBlendOverrides();
            blend = nativeWater.getDirectives().getBlendModeOverride()
                               .orElse(nativeShadowAdapter ? BlendModeOverride.OFF : null);
        } else if (contract != null) {
            ContractProperties properties = ((ContractShaderPack) accessor.flywheel$pack()).flywheel$contractProperties();
            bufferBlendInformation = properties.bufferBlend(contract);
            blend = properties.blend(contract);
            if (blend == null && shadow) {
                blend = BlendModeOverride.OFF;
            }
        } else {
            bufferBlendInformation = source.getDirectives()
                                           .getBufferBlendOverrides();
            blend = source.getDirectives()
                          .getBlendModeOverride()
                          .orElse(key.role() == PackRole.GLINT ? GLINT_BLEND
                                  : key.role() == PackRole.ENTITIES_TRANSLUCENT ? ENTITY_TRANSLUCENT_BLEND
                                  : key.role().programId.getBlendModeOverride());
        }
        if (blend == null && key.role() == PackRole.ADDITIVE && deferredEmissive(pipeline)) {
            blend = BlendModeOverride.OFF;
        }
        BlendModeOverride finalBlend = blend;
        boolean grayscaleFont = GRAYSCALE_FONT.equals(key.materialFragment()) && (contract == null
                || nativeAdapter || nativeShadowAdapter
                || source.getFragmentSource().orElseThrow().contains(ContractPatches.NATIVE_ADDITIVE));
        return new PreparedProgram(label, stages, programId -> {
            GuestProgram program = new GuestProgram(programId, label,
                    renderPipeline.getBindGroupLayouts(), key.crumbling(), pipeline,
                    shadow, packTarget(accessor, pipeline, drawBuffers, shadow), finalBlend,
                    bufferBlends(bufferBlendInformation, drawBuffers), finalAlpha, List.of());
            if (grayscaleFont) program.useGrayscaleAlbedo();
            return program;
        });
    }

    private static GuestProgram composite(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                          boolean shadow) {
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        ContractProperties properties = Objects.requireNonNull(oitProperties(pipeline, shadow));
        ProgramSource source = Objects.requireNonNull(oitSource(accessor, shadow));
        ContractProgram program = Objects.requireNonNull(ContractProgram.byName(source.getName()));
        int[] drawBuffers = drawBuffers(source, shadow);
        GuestShaders.OitSpec spec = new GuestShaders.OitSpec(null, properties.oit(shadow), drawBuffers, shadow);
        String label = "flywheel:iris/" + source.getName() + "/oit_composite";
        int programId = linkProgram(label, new GuestShaders.Stages(GuestOitCodegen.COMPOSITE_VERTEX, null, null, null,
                GuestOitCodegen.compositeFragment(spec)));

        GuestOitTargets targets = oitTargets(accessor, shadow);
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
                pipeline, shadow, packTarget(accessor, pipeline, drawBuffers, shadow), finalBlend,
                bufferBlends(properties.bufferBlend(program), drawBuffers), AlphaTests.OFF, textures));
    }

    private static GuestProgram compositeDepth(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                               boolean shadow) {
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        ProgramSource source = Objects.requireNonNull(oitSource(accessor, shadow));
        String label = "flywheel:iris/" + source.getName() + "/oit_depth";
        int programId = linkProgram(label, new GuestShaders.Stages(GuestOitCodegen.COMPOSITE_VERTEX, null, null, null,
                GuestOitCodegen.depthFragment(shadow)));
        GuestOitTargets targets = oitTargets(accessor, shadow);
        return publishDirect(programId, id -> new GuestProgram(id, label, renderPipeline.getBindGroupLayouts(), false,
                pipeline, shadow, packTarget(accessor, pipeline, drawBuffers(source, shadow), shadow), null, List.of(),
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

    private static GuestProgram.Target packTarget(IrisRenderingPipelineAccessor accessor,
                                                  IrisRenderingPipeline pipeline, int[] drawBuffers, boolean shadow) {
        if (shadow) {
            GlFramebuffer framebuffer = accessor.flywheel$shadowTargets()
                                                .get()
                                                .createShadowFramebuffer(ImmutableSet.of(), drawBuffers);
            return before -> framebuffer.bind();
        }
        RenderTargets targets = accessor.flywheel$renderTargets();
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
                shaders[count++] = batch.sharedShader(types[index], sources[index], label);
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

    private static AlphaTest cutoutAlphaTest(CutoutShader cutout) {
        if (cutout == CutoutShaders.EPSILON) {
            return AlphaTests.NON_ZERO_ALPHA;
        } else if (cutout == CutoutShaders.ONE_TENTH) {
            return AlphaTests.ONE_TENTH_ALPHA;
        } else if (cutout == CutoutShaders.HALF) {
            return AlphaTests.HALF_ALPHA;
        }
        // TODO: CLIP_SLAB/CLIP_HALFSPACE discard on _flw_clipData, which pack fragment stages never read.
        return AlphaTests.OFF;
    }

    record ProgramKey(PackRole role, boolean indirect, @Nullable InstanceType<?> type, Identifier materialVertex,
                      AlphaTest alphaTest, boolean embedded, boolean crumbling, int typeGen,
                      @Nullable Transparency transparency, @Nullable CutoutShader cutout,
                      @Nullable Identifier materialFragment, @Nullable Identifier light,
                      @Nullable LightSmoothness smoothness, @Nullable OitPass oit, @Nullable Identifier fog,
                      boolean unlit, GlslVersion glsl, boolean selector) {
        static ProgramKey of(PackRole role, boolean indirect, @Nullable InstanceType<?> type, Material material,
                             boolean embedded, boolean crumbling, int typeGen) {
            return new ProgramKey(role, indirect, type, material.shaders()
                                                                .vertexSource(),
                    crumbling ? AlphaTests.OFF : cutoutAlphaTest(material.cutout()), embedded, crumbling, typeGen,
                    material.transparency(), material.cutout(), material.shaders()
                                                                        .fragmentSource(), material.light()
                                                                                                   .source(),
                    BackendConfig.INSTANCE.lightSmoothness(), null, material.fog().source(), !material.useLight(),
                    GuestShaders.glslVersion(), !indirect && GlCompat.USE_INSTANCING_SELECTOR);
        }

        ProgramKey withOit(OitPass pass) {
            return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                    transparency, cutout, materialFragment, light, smoothness, pass, fog, unlit, glsl, selector);
        }

        ProgramKey forNative() {
            boolean vertexLight = !crumbling && role != PackRole.ADDITIVE;
            return new ProgramKey(role == PackRole.BLOCK_ENTITY ? PackRole.SOLID : role, indirect, type, materialVertex,
                    alphaTest,
                    embedded && (!indirect || vertexLight), crumbling, typeGen, null, null,
                    GRAYSCALE_FONT.equals(materialFragment) ? materialFragment : null, light, smoothness, null, null,
                    false, glsl, selector);
        }

        ProgramKey forContract() {
            return new ProgramKey(role == PackRole.TERRAIN_TRANSLUCENT ? role : role.blockRole(), indirect, type,
                    materialVertex, AlphaTests.OFF, embedded, crumbling,
                    typeGen,
                    role == PackRole.ADDITIVE ? transparency : null, cutout, materialFragment, light, smoothness, oit,
                    fog, unlit, glsl, selector);
        }

        String cacheName() {
            return (role.name() + (indirect ? "_indirect_" : "_instancing_")
                    + (type == null ? "uber" + typeGen : ResourceUtil.toDebugFileNameNoExtension(type.vertexShader()))
                    + "_" + ResourceUtil.toDebugFileNameNoExtension(materialVertex) + "_" + alphaTest.function() + "_"
                    + alphaTest.reference() + (embedded ? "_embed" : "") + (crumbling ? "_crumbling" : "")
                    + (materialFragment == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(materialFragment))
                    + (light == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(light))
                    + (cutout == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(cutout.source()))
                    + (smoothness == null ? "" : "_" + smoothness.getSerializedName())
                    + (fog == null ? "" : "_" + ResourceUtil.toDebugFileNameNoExtension(fog))
                    + (unlit ? "_unlit" : "")
                    + (oit == null ? "" : "_oit_" + oit.name())
                    + (glsl == GlslVersion.V460 ? "" : "_glsl" + glsl.version)
                    + (selector ? "_selector" : ""))
                    .toLowerCase(Locale.ROOT);
        }
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
