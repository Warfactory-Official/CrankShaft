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
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.CrumblingPipelines;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.instancing.InstancingPipeline;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.SundialTranslucent;
import dev.engine_room.flywheel.iris.engine.GuestVertexExtras;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
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

/**
 * Guest {@link RenderPipeline}s (pack-independent state + bind group) and their programs, compiled against the
 * current {@link IrisRenderingPipeline}. Render thread only.
 */
public final class GuestPipelines {
    private static final Identifier GUEST_SHADER = ResourceUtil.rl("iris/guest");

    private static final Map<PipelineKey, RenderPipeline> PIPELINES = new HashMap<>();
    private static final Map<RenderPipeline, ProgramKey> PROGRAM_KEYS = new IdentityHashMap<>();

    private static final Map<RenderPipeline, Boolean> COMPOSITE_SHADOW = new IdentityHashMap<>();
    private static final RenderPipeline[] COMPOSITES = new RenderPipeline[2];
    private static final BlendFunction MAX_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX,
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    private static final BlendFunction ADD_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE);
    private static final BlendFunction COMPOSITE_BLEND = new BlendFunction(BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final Map<LinkKey, GuestProgram> PROGRAMS = new HashMap<>();
    private static final Map<RenderPipeline, GlRenderPipeline> COMPILED = new IdentityHashMap<>();
    private static final Map<RenderPipeline, GlRenderPipeline> MESH_COMPILED = new IdentityHashMap<>();
    private static final GuestOitTargets[] OIT_TARGETS = new GuestOitTargets[2];
    private static @Nullable IrisRenderingPipeline owner;

    private GuestPipelines() {
    }

    public static RenderPipeline instancing(PackRole role, Material material, InstanceType<?> type, boolean embedded) {
        ProgramKey program = ProgramKey.of(role, false, type, material, embedded, false, 0);
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(role, InstancingPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), embedded), k));
    }

    /**
     * Type-erased, like {@code IndirectPipeline.uberPipelineFor}; guests never sample bindless. {@code embedded}: the
     * embedded fragment variant (the vertex branches on the draw's matrix index).
     */
    public static RenderPipeline indirect(PackRole role, Material material, boolean embedded) {
        ProgramKey program = ProgramKey.of(role, true, null, material, embedded, false, InstanceTypeIds.snapshot()
                                                                                                       .types()
                                                                                                       .size());
        return PIPELINES.computeIfAbsent(materialKey(program, material),
                k -> register(role, IndirectPipeline.stateBuilder(k.transparency(), k.depthTest(), k.depthWrite(),
                        k.colorWrite(), k.cull(), k.polygonOffset(), false), k));
    }

    /**
     * {@link #instancing} as an OIT producer pass; {@code role}: {@code TRANSLUCENT} or {@code SHADOW}.
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
     * Fullscreen resolve of the OIT targets into the contract translucent program's framebuffer.
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
                                                      new DepthStencilState(CompareOp.ALWAYS_PASS, true, 0.0f, 0.0f))
                                              .withCull(false)
                                              .withColorTargetState(new ColorTargetState(Optional.of(COMPOSITE_BLEND),
                                                      GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                                              .build();
            COMPOSITE_SHADOW.put(COMPOSITES[index], shadow);
        }
        return COMPOSITES[index];
    }

    private static PipelineKey materialKey(ProgramKey program, Material material) {
        boolean shadow = program.role().shadow;
        return new PipelineKey(program, material.transparency(), material.depthTest(), shadow || material.writeMask()
                                                                                                         .depth(),
                material.writeMask()
                        .color(), !shadow && material.backfaceCulling(), !shadow && material.polygonOffset());
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
            // Shadow depth is forward-Z (cleared to 1); Iris flips compares only for its own pipelines.
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
     * The compiled guest for {@code pipeline}, or {@code null} for any other pipeline.
     */
    public static @Nullable GlRenderPipeline compiled(RenderPipeline pipeline) {
        ProgramKey key = PROGRAM_KEYS.get(pipeline);
        Boolean compositeShadow = COMPOSITE_SHADOW.get(pipeline);
        // The engine's own terrain pipelines, claimed by identity so no guest-specific pipeline object is needed.
        // Only while a pack is live: these pipelines are also warmed up with no pack, where there is no guest.
        TerrainPipelines.Kind terrainKind = GuestTerrainGate.ENABLED && Iris.isPackInUseQuick()
                ? TerrainPipelines.terrainKind(pipeline) : null;
        if (key == null && compositeShadow == null && terrainKind == null) {
            return null;
        }
        IrisRenderingPipeline current = currentPipeline();
        track(current);
        boolean mesh = terrainKind != null && GuestTerrainGate.meshActive;
        Map<RenderPipeline, GlRenderPipeline> cache = mesh ? MESH_COMPILED : COMPILED;
        GlRenderPipeline compiled = cache.get(pipeline);
        if (compiled == null) {
            GuestProgram program = key != null ? program(current, pipeline, key)
                    : terrainKind != null ? terrain(current, pipeline, terrainKind, mesh)
                    : composite(current, pipeline, compositeShadow);
            compiled = new GlRenderPipeline(pipeline, program);
            cache.put(pipeline, compiled);
        }
        return compiled;
    }

    /**
     * The engine's MDI terrain draw through the pack's {@code gbuffers_terrain}; alpha mirrors Iris's own
     * {@code SODIUM_TERRAIN_*} keys.
     */
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
            throw new IllegalStateException("Shaderpack has no program for " + programId);
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

        GuestProgram program = new GuestProgram(programId2, label, renderPipeline.getBindGroupLayouts(), false,
                pipeline, shadow,
                packTarget(accessor, pipeline, drawBuffers, shadow), source.getDirectives()
                                                                           .getBlendModeOverride()
                                                                           .orElse(programId.getBlendModeOverride()),
                bufferBlends(source.getDirectives()
                                   .getBufferBlendOverrides(), drawBuffers), alpha, meshTextures(mesh, List.of()));
        program.useTerrainState();
        if (mesh) program.useMesh(meshQuads, taskQuads, taskRecovery, compactSafe);
        return program;
    }

    /**
     * The engine's translucent terrain stream as an OIT producer: the pack's own {@code gbuffers_water} shading,
     * accumulated into the guest OIT targets the instance producers already write, so one composite resolves both.
     */
    private static GuestProgram terrainOit(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline,
                                           ProgramSource source, AlphaTest alpha, int[] drawBuffers, int oitPass,
                                           boolean mesh) {
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        ContractProperties properties = Objects.requireNonNull(oitProperties(pipeline, false));
        OitPass pass = OitPass.values()[oitPass];
        // The shared targets use the contract's layout; terrain outputs are matched by colortex identity.
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
        GuestProgram program = new GuestProgram(programId, label, renderPipeline.getBindGroupLayouts(), false,
                pipeline, false,
                before -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                        oitPass == 0 ? targets.depthRangeFbo()
                                : oitPass == 1 ? targets.coefficientsFbo() : targets.accumulateFbo()),
                null, List.of(), alpha, meshTextures(mesh, producerTextures(targets, pass)));
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

    /**
     * Sodium's attribute names, in the live layout's element order: a guest program links itself, so nothing else
     * binds them.
     */
    private static String[] terrainAttributes() {
        return TerrainVertexFormat.current()
                                  .getElements()
                                  .stream()
                                  .map(VertexFormatElement::name)
                                  .toArray(String[]::new);
    }

    /**
     * Called on the render thread when Iris destroys a pipeline; releases the guest programs and OIT targets it owns.
     */
    public static void release(IrisRenderingPipeline pipeline) {
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
    }

    /**
     * Colorwheel OIT ({@code colorwheel.properties oit}) applies to the group's order-independent draws.
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
        ProgramSource source = oitSource((IrisRenderingPipelineAccessor) pipeline, shadow);
        return source != null && !hasExtraStages(source);
    }

    public static boolean deferredOitActive(IrisRenderingPipeline pipeline) {
        return contractSet((IrisRenderingPipelineAccessor) pipeline).flywheel$deferredOit() != null;
    }

    /**
     * Resizes, attaches and clears the group's OIT targets; returns whether it has coefficient sets.
     */
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
                formats[slot] = oit.accumulate(drawBuffers[slot])
                                   .format()
                                   .getGlFormat();
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

    /**
     * {@code colorwheel.properties} of a pack that ships the Colorwheel contract, else {@code null}.
     */
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
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        ContractProgramSet contractSet = contractSet(accessor);
        ProgramSource contract = contractSet.flywheel$contractSource(
                ContractProgram.of(key.role(), Objects.requireNonNull(key.transparency()),
                        contractSet::flywheel$hasContract));
        // Colorwheel defines no tessellation contract.
        if (contract != null && (contract.getTessControlSource()
                                         .isPresent() || contract.getTessEvalSource()
                                                                 .isPresent())) {
            FlwBackend.LOGGER.warn("{} has tessellation stages; drawing through the pack's own programs",
                    contract.getName());
            contract = null;
        }
        if (contract == null) {
            if (key.oit() != null) {
                throw new IllegalStateException("OIT producer without a contract translucent program");
            }
            return PROGRAMS.computeIfAbsent(new LinkKey(key.forNative(), null),
                    k -> link(pipeline, renderPipeline, k.program(), null, nativeSource(accessor, key.role())));
        }
        ContractProgram id = Objects.requireNonNull(ContractProgram.byName(contract.getName()));
        ProgramSource source = contract;
        return PROGRAMS.computeIfAbsent(new LinkKey(key.forContract(), id),
                k -> link(pipeline, renderPipeline, k.program(), id, source));
    }

    private static ProgramSource nativeSource(IrisRenderingPipelineAccessor accessor, PackRole role) {
        ProgramSource source = accessor.flywheel$resolver()
                                       .resolveNullable(role.programId);
        if (source == null) {
            throw new IllegalStateException("Shaderpack has no program for " + role.programId);
        }
        return source;
    }

    private static GuestProgram link(IrisRenderingPipeline pipeline, RenderPipeline renderPipeline, ProgramKey key,
                                     @Nullable ContractProgram contract, ProgramSource source) {
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        // Authored Colorwheel contracts discard through their material cutout.
        AlphaTest alpha = contract != null ? AlphaTests.OFF : source.getDirectives()
                                                                    .getAlphaTestOverride()
                                                                    .orElse(key.alphaTest());
        boolean nativeAdapter = source.getFragmentSource().orElseThrow().contains(ContractPatches.NATIVE_TRANSLUCENT);
        boolean nativeShadowAdapter = source.getFragmentSource().orElseThrow()
                                            .contains(ContractPatches.NATIVE_SHADOW_TRANSLUCENT);
        if (contract != null && (nativeAdapter || source.getFragmentSource().orElseThrow()
                                                        .contains(SundialTranslucent.MARKER))) {
            // Native-adapter uniforms use the same alpha reference as their source program.
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
        int programId = linkProgram(label, stages);

        if (oit != null) {
            GuestOitTargets targets = oitTargets(accessor, shadow);
            int fboIndex = pass.ordinal();
            return new GuestProgram(programId, label, renderPipeline.getBindGroupLayouts(), false, pipeline, shadow,
                    before -> GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,
                            fboIndex == 0 ? targets.depthRangeFbo()
                                    : fboIndex == 1 ? targets.coefficientsFbo() : targets.accumulateFbo()), null,
                    List.of(), alpha, producerTextures(targets, pass));
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
                          .orElse(key.role().programId.getBlendModeOverride());
        }
        return new GuestProgram(programId, label, renderPipeline.getBindGroupLayouts(), key.crumbling(), pipeline,
                shadow, packTarget(accessor, pipeline, drawBuffers, shadow), blend,
                bufferBlends(bufferBlendInformation, drawBuffers), alpha, List.of());
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
        return new GuestProgram(programId, label, renderPipeline.getBindGroupLayouts(), false, pipeline, shadow,
                packTarget(accessor, pipeline, drawBuffers, shadow), blend,
                bufferBlends(properties.bufferBlend(program), drawBuffers), AlphaTests.OFF, textures);
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
        List<Integer> shaders = new ArrayList<>(5);
        boolean linked = false;
        try {
            for (int i = 0; i < types.length; i++) attach(programId, shaders, label, types[i], sources[i]);
            for (int location = 0; location < attributes.length; location++) {
                GlStateManager._glBindAttribLocation(programId, location, attributes[location]);
            }
            GlStateManager.glLinkProgram(programId);
            if (GlStateManager.glGetProgrami(programId, GL20C.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("Failed to link " + label + ": "
                        + GlStateManager.glGetProgramInfoLog(programId, 32768));
            }
            linked = true;
            return programId;
        } finally {
            for (int shader : shaders) {
                GL20C.glDetachShader(programId, shader);
                GlStateManager.glDeleteShader(shader);
            }
            // A stage can fail before the program reaches any cache owner.
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
        int shader = GlStateManager.glCreateShader(glType);
        GlStateManager.glShaderSource(shader, source);
        GlStateManager.glCompileShader(shader);
        if (GlStateManager.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GlStateManager.glGetShaderInfoLog(shader, 32768);
            GlStateManager.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile " + label + " (" + glType + "): " + log);
        }
        GlStateManager.glAttachShader(programId, shader);
        shaders.add(shader);
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

    /**
     * {@code type}: {@code null} for the type-erased indirect vertex, whose {@code typeGen} is the registered type
     * count. Nullable tail: the contract fragment inputs, cleared by {@link #forNative}.
     */
    record ProgramKey(PackRole role, boolean indirect, @Nullable InstanceType<?> type, Identifier materialVertex,
                      AlphaTest alphaTest, boolean embedded, boolean crumbling, int typeGen,
                      @Nullable Transparency transparency, @Nullable CutoutShader cutout,
                      @Nullable Identifier materialFragment, @Nullable Identifier light,
                      @Nullable LightSmoothness smoothness, @Nullable OitPass oit) {
        static ProgramKey of(PackRole role, boolean indirect, @Nullable InstanceType<?> type, Material material,
                             boolean embedded, boolean crumbling, int typeGen) {
            return new ProgramKey(role, indirect, type, material.shaders()
                                                                .vertexSource(),
                    crumbling ? AlphaTests.OFF : cutoutAlphaTest(material.cutout()), embedded, crumbling, typeGen,
                    material.transparency(), material.cutout(), material.shaders()
                                                                        .fragmentSource(), material.light()
                                                                                                   .source(),
                    BackendConfig.INSTANCE.lightSmoothness(), null);
        }

        ProgramKey withOit(OitPass pass) {
            return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded, crumbling, typeGen,
                    transparency, cutout, materialFragment, light, smoothness, pass);
        }

        // Indirect native vertex stages branch on the matrix index at runtime: no embedded variant.
        ProgramKey forNative() {
            return new ProgramKey(role, indirect, type, materialVertex, alphaTest, embedded && !indirect, crumbling,
                    typeGen, null, null, null, null, null, null);
        }

        // Colorwheel programs serve entities and block entities alike.
        ProgramKey forContract() {
            return new ProgramKey(role.blockRole(), indirect, type, materialVertex, AlphaTests.OFF, embedded, crumbling,
                    typeGen,
                    null, cutout, materialFragment, light, smoothness, oit);
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
                    + (oit == null ? "" : "_oit_" + oit.name()))
                    .toLowerCase(Locale.ROOT);
        }
    }

    /**
     * {@code contract}: the resolved Colorwheel program, {@code null} for the pack's own program.
     */
    private record LinkKey(ProgramKey program, @Nullable ContractProgram contract) {
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
