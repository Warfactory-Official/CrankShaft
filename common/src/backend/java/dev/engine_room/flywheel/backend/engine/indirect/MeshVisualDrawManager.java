package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.*;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.backend.gl.shader.MeshGlPrograms;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class MeshVisualDrawManager extends IndirectDrawManager {
    private static final int UNIT_ATLAS = 0;
    private static final int UNIT_OVERLAY = 1;
    private static final int UNIT_LIGHTMAP = 2;
    private static final int UNIT_CRACK = 3;
    private static final int UNIT_OIT_DEPTH_RANGE = 4;
    private static final int UNIT_OIT_BLUE_NOISE = 5;
    private static final int UNIT_OIT_COEFF0 = 6;
    private static final int UBO_FOG = 2;
    private static final int UBO_DYNAMIC_TRANSFORMS = 3;
    private static final int MESH_TASK_COMMAND_STRIDE = 8;
    private static final int SSBO_MESH_TASK_COMMANDS = 15;
    private static final int SSBO_MESH_VERTICES = 13;
    private static final int SSBO_MESH_INDICES = 14;
    private static final int SSBO_MESHLET_BOUNDS = 16;
    private final Map<MeshProgramKey, ProgramVersions<GlProgramBuild>> meshPrograms = new HashMap<>();
    private final Map<CrumblingProgramKey, ProgramVersions<GlProgramBuild>> crumblingPrograms = new HashMap<>();
    private final Map<OitProgramKey, ProgramVersions<@Nullable GlProgramBuild[]>> oitPrograms = new HashMap<>();
    private int commandBuilderProgram = -1;
    private int commandBuilderDrawCountLoc = -1;
    private int commandBuilderWriteOffsetLoc = -1;
    private int commandBuffer = 0;
    private int commandBufferCapacity = 0; // in commands
    private int builtDraws = 0;
    private int matrixUbo = 0;
    private int atlasSampler = 0;
    private int lightmapSampler = 0;
    private int crackSampler = 0;

    public MeshVisualDrawManager(IndirectPrograms programs) {
        super(programs);
    }

    private static void applyMaterialState(Material material) {
        Transparency transparency = material.transparency();
        switch (transparency) {
            case OPAQUE -> GlStateManager._disableBlend(0);
            case ADDITIVE -> {
                GlStateManager._enableBlend(0);
                GlStateManager._blendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);
            }
            case LIGHTNING, ORDER_INDEPENDENT_ADDITIVE -> {
                GlStateManager._enableBlend(0);
                GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_SRC_ALPHA, GL11C.GL_ONE);
            }
            case GLINT -> {
                GlStateManager._enableBlend(0);
                GlStateManager._blendFuncSeparate(GL11C.GL_SRC_COLOR, GL11C.GL_ONE, GL11C.GL_ZERO, GL11C.GL_ONE);
            }
            case CRUMBLING, TRANSLUCENT, ORDER_INDEPENDENT -> {
                GlStateManager._enableBlend(0);
                GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                        GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            }
            case TRANSLUCENT_ALPHA_REPLACE -> {
                GlStateManager._enableBlend(0);
                GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                        GL11C.GL_ONE, GL11C.GL_ZERO);
            }
        }
        DepthTest depthTest = material.depthTest();
        GlStateManager._depthFunc(depthFunc(depthTest));
        GlStateManager._depthMask(material.writeMask()
                                          .depth());
        GlStateManager._colorMask(material.writeMask()
                                          .color() ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE);
        if (material.backfaceCulling()) {
            GlStateManager._enableCull();
        } else {
            GlStateManager._disableCull();
        }
        if (material.polygonOffset()) {
            GlStateManager._polygonOffset(MaterialRenderState.polygonOffsetSlope(transparency, depthTest),
                    MaterialRenderState.POLYGON_OFFSET_UNITS);
            GlStateManager._enablePolygonOffset();
        } else {
            GlStateManager._disablePolygonOffset();
        }
    }

    private static int depthFunc(DepthTest depthTest) {
        return switch (depthTest) {
            case OFF, ALWAYS -> GL11C.GL_ALWAYS;
            case NEVER -> GL11C.GL_NEVER;
            case LESS -> GL11C.GL_GREATER;
            case EQUAL -> GL11C.GL_EQUAL;
            case LEQUAL -> GL11C.GL_GEQUAL;
            case GREATER -> GL11C.GL_LESS;
            case NOTEQUAL -> GL11C.GL_NOTEQUAL;
            case GEQUAL -> GL11C.GL_LEQUAL;
        };
    }

    private static Stages meshStages(MeshProgramKey key) {
        var stages = MeshVisualShaders.clipExtra(key.type()).andThen(embeddedExtra(key.embedded()));
        return new Stages("meshvisual:" + key.type() + ":" + key.shaders() + ":" + key.light().source()
                + (key.embedded() ? ":embedded" : "")
                + (key.debug() == DebugMode.OFF ? "" : ":debug_" + key.debug().getSerializedName()),
                new int[]{NVMeshShader.GL_TASK_SHADER_NV, NVMeshShader.GL_MESH_SHADER_NV, GL20C.GL_FRAGMENT_SHADER},
                new String[]{"meshvisual task", "meshvisual mesh", "meshvisual frag"},
                new String[]{MeshVisualShaders.assembleTask(key.type()),
                        MeshVisualShaders.assembleMesh(key.type(), key.shaders().vertexSource(),
                                RenderPassShaders.maybeBindlessGl().andThen(stages)
                                                 .andThen(RenderPassShaders.debugExtra(key.debug()))),
                        MeshVisualShaders.assembleFragment(key.light(), key.shaders().fragmentSource(),
                                key.smoothness(), RenderPassShaders.maybeBindlessGl()
                                                                   .andThen(MeshVisualShaders.GL_MESH_F16)
                                                                   .andThen(stages)
                                                                   .andThen(RenderPassShaders.debugExtra(
                                                                           key.debug())))});
    }

    private static void meshSetup(int program) {
        bindCommonProgramState(program);
        GlStateTracker.useProgram(0);
    }

    private static Stages crumblingStages(CrumblingProgramKey key) {
        var debug = RenderPassShaders.debugExtra(key.debug());
        return new Stages("meshvisual:crumbling:" + key.type()
                + (key.debug() == DebugMode.OFF ? "" : ":debug_" + key.debug().getSerializedName()),
                new int[]{NVMeshShader.GL_MESH_SHADER_NV, GL20C.GL_FRAGMENT_SHADER},
                new String[]{"meshvisual crumbling mesh", "meshvisual crumbling frag"},
                new String[]{MeshVisualShaders.assembleCrumblingMesh(key.type(), debug),
                        MeshVisualShaders.assembleCrumblingFragment(key.smoothness(), debug)});
    }

    private static void crumblingSetup(int program) {
        bindCommonProgramState(program);
        setSampler(program, "_flw_crumblingTex", UNIT_CRACK);
        GlStateTracker.useProgram(0);
    }

    private static Consumer<Compilation> embeddedExtra(boolean embedded) {
        return embedded ? RenderPassShaders.EMBEDDED : ShaderAssembly.NO_EXTRA;
    }

    private static Stages oitStages(MeshProgramKey key, OitMode mode, boolean emission) {
        var stages = MeshVisualShaders.clipExtra(key.type()).andThen(embeddedExtra(key.embedded()));
        var debug = RenderPassShaders.debugExtra(key.debug());
        return new Stages("meshvisual:oit" + mode.name + ":" + key.type() + ":" + key.shaders() + ":"
                + key.light().source() + (key.embedded() ? ":embedded" : "")
                + (key.debug() == DebugMode.OFF ? "" : ":debug_" + key.debug().getSerializedName()),
                new int[]{NVMeshShader.GL_TASK_SHADER_NV, NVMeshShader.GL_MESH_SHADER_NV, GL20C.GL_FRAGMENT_SHADER},
                new String[]{"meshvisual oit task", "meshvisual oit mesh", "meshvisual oit frag" + mode.name},
                new String[]{MeshVisualShaders.assembleTask(key.type()),
                        MeshVisualShaders.assembleMesh(key.type(), key.shaders().vertexSource(),
                                RenderPassShaders.maybeBindlessGl().andThen(stages).andThen(debug)),
                        MeshVisualShaders.assembleOitFragment(mode, key.light(), key.shaders().fragmentSource(),
                                key.smoothness(), false, RenderPassShaders.maybeBindlessGl()
                                                                          .andThen(MeshVisualShaders.GL_MESH_F16)
                                                                          .andThen(stages)
                                                                          .andThen(debug)
                                                                          .andThen(emission
                                                                                  ? RenderPassShaders.EMISSION_PRODUCER
                                                                                  : ShaderAssembly.NO_EXTRA))});
    }

    private static void oitSetup(int program) {
        bindCommonProgramState(program);
        setSampler(program, "_flw_depthRange", UNIT_OIT_DEPTH_RANGE);
        setSampler(program, "_flw_blueNoise", UNIT_OIT_BLUE_NOISE);
        if (!OitConfig.coefficientArray()) {
            setSampler(program, "_flw_coefficients0", UNIT_OIT_COEFF0);
            setSampler(program, "_flw_coefficients1", UNIT_OIT_COEFF0 + 1);
            setSampler(program, "_flw_coefficients2", UNIT_OIT_COEFF0 + 2);
            setSampler(program, "_flw_coefficients3", UNIT_OIT_COEFF0 + 3);
        }
        GlStateTracker.useProgram(0);
    }

    private static void bindCommonProgramState(int program) {
        bindBlock(program, "Fog", UBO_FOG);
        bindBlock(program, "DynamicTransforms", UBO_DYNAMIC_TRANSFORMS);
        GlStateTracker.useProgram(program);
        setSampler(program, "Sampler0", UNIT_ATLAS);
        setSampler(program, "Sampler1", UNIT_OVERLAY);
        setSampler(program, "Sampler2", UNIT_LIGHTMAP);
    }

    private static int buildCommandBuilder() {
        int compute = MeshGlPrograms.compileShader("meshvisual", GL43C.GL_COMPUTE_SHADER, "meshvisual command_builder",
                MeshVisualShaders.assembleCommandBuilder());
        int program = compute != 0 ? MeshGlPrograms.linkProgram("meshvisual", "meshvisual:command_builder",
                compute) : 0;
        if (compute != 0) {
            GL20C.glDeleteShader(compute);
        }
        return program;
    }

    public static void warmUp(List<InstanceType<?>> types, List<InstanceType<?>> crumblingTypes,
                              List<Material> materials, boolean wavelet) {
        deleteWarm(buildCommandBuilder());
        for (InstanceType<?> type : crumblingTypes) {
            linkAndDelete(crumblingStages(CrumblingProgramKey.of(type, DebugMode.OFF)));
        }
        Set<MeshProgramKey> solidDone = new HashSet<>();
        Set<MeshProgramKey> oitDone = new HashSet<>();
        for (InstanceType<?> type : types) {
            for (Material material : materials) {
                MeshProgramKey key = MeshProgramKey.of(type, material, false, DebugMode.OFF);
                if (!OitTransparency.orderIndependent(material)) {
                    if (solidDone.add(key)) {
                        linkAndDelete(meshStages(key));
                    }
                } else if (wavelet && oitDone.add(key)) {
                    for (OitMode mode : OitMode.values()) {
                        if (mode != OitMode.OFF) {
                            linkAndDelete(oitStages(key, mode, false));
                        }
                    }
                }
            }
        }
    }

    // Warms the driver's shader cache.
    private static void linkAndDelete(Stages stages) {
        int[] shaders = new int[stages.types().length];
        boolean compiled = true;
        for (int i = 0; i < shaders.length; i++) {
            shaders[i] = MeshGlPrograms.compileShader("meshvisual", stages.types()[i], stages.stageLabels()[i],
                    stages.sources()[i]);
            compiled &= shaders[i] != 0;
        }
        if (compiled) deleteWarm(MeshGlPrograms.linkProgram("meshvisual", stages.label(), shaders));
        for (int shader : shaders) {
            if (shader != 0) GL20C.glDeleteShader(shader);
        }
    }

    private static GlProgramBuild request(Stages stages, IntConsumer setup) {
        String[] sources = new String[stages.types().length];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = MeshGlPrograms.driverSource("meshvisual", stages.types()[i], stages.stageLabels()[i],
                    stages.sources()[i]);
        }
        return GlProgramBuild.request(stages.label(), stages.types(), sources, stages.stageLabels(),
                ProgramAvailability.Feature.MESH, setup);
    }

    private static void deleteWarm(int program) {
        if (program != 0) {
            GL20C.glDeleteProgram(program);
        }
    }

    private static void setupOpaqueState() {
        GlStateManager._disableBlend(0);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_GEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
        GlStateManager._enableCull();
        GlStateManager._disablePolygonOffset();
    }

    private static void bindTextureCoherent(int unit, @Nullable GpuTextureView view) {
        if (view == null || !(view.texture() instanceof GlTexture glTexture)) {
            return;
        }
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(glTexture.glId());
        GlTextureLevelState.applyMipLevels(glTexture, view.baseMipLevel(), view.baseMipLevel() + view.mipLevels() - 1);
    }

    private static void bindTaskCull(@Nullable GpuBuffer meshletBounds) {
        FrameUniforms.bind();
        if (meshletBounds != null) {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SSBO_MESHLET_BOUNDS,
                    ((GlBuffer) meshletBounds).handle());
        }
    }

    private static void setSampler(int program, String name, int unit) {
        int loc = GL20C.glGetUniformLocation(program, name);
        if (loc >= 0) {
            GL20C.glUniform1i(loc, unit);
        }
    }

    private static void bindBlock(int program, String name, int binding) {
        int index = GL31C.glGetUniformBlockIndex(program, name);
        if (index != GL31C.GL_INVALID_INDEX) {
            GL31C.glUniformBlockBinding(program, index, binding);
        }
    }

    private static void applyOitMaterialState(Material material) {
        GlStateManager._depthFunc(depthFunc(material.depthTest()));
        if (material.backfaceCulling()) {
            GlStateManager._enableCull();
        } else {
            GlStateManager._disableCull();
        }
        if (material.polygonOffset()) {
            GlStateManager._polygonOffset(1.0f, 10.0f);
            GlStateManager._enablePolygonOffset();
        } else {
            GlStateManager._disablePolygonOffset();
        }
    }

    @Override
    boolean wantsMeshletBounds() {
        return true;
    }

    @Override
    void submitSolid(Matrix4fc modelView) {
        builtDraws = 0;
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }
        int vertexId = ((GlBuffer) vertexBuffer).handle();
        int indexId = ((GlBuffer) indexBuffer).handle();

        int cmdBuilder = ensureCommandBuilder();
        if (cmdBuilder == 0) {
            return;
        }

        int totalDraws = frameDrawCount;
        if (totalDraws == 0) {
            return;
        }
        ensureCommandBuffer(totalDraws);

        GlStateTracker.useProgram(cmdBuilder);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SSBO_MESH_TASK_COMMANDS, commandBuffer);
        buffers.bindForDraw(); // global SSBO 1,2,3,4 (drawCommands at 4)
        GL30C.glUniform1ui(commandBuilderDrawCountLoc, totalDraws);
        GL30C.glUniform1ui(commandBuilderWriteOffsetLoc, 0);
        GL43C.glDispatchCompute((totalDraws + 63) / 64, 1, 1);
        GL42C.glMemoryBarrier(GL42C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        GlStateTracker.useProgram(0);
        builtDraws = totalDraws;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        GpuTextureView lightmapView = mc.gameRenderer.lightmap();
        TextureManager textureManager = mc.getTextureManager();

        ensureSamplers();
        uploadModelViewUbo(modelView);
        GpuBufferSlice projSlice = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice lightSlice = RenderSystem.getShaderLights();
        GpuBufferSlice fogSlice = RenderSystem.getShaderFog();
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(modelView));
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();

        CommandEncoder encoder = RenderSystem.getDevice()
                                             .createCommandEncoder();

        try (RenderPass _ = encoder.createRenderPass(() -> "flywheel:meshvisual/opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            setupOpaqueState();
            bindTextureCoherent(UNIT_LIGHTMAP, lightmapView);
            bindTextureCoherent(UNIT_OVERLAY, overlayView);
            GL33C.glBindSampler(UNIT_ATLAS, atlasSampler);
            GL33C.glBindSampler(UNIT_OVERLAY, 0);
            GL33C.glBindSampler(UNIT_LIGHTMAP, lightmapSampler);

            bindSharedFrameUbos(projSlice, lightSlice, fogSlice, dynamicTransforms,
                    renderPassUniforms.renderOriginSlice(), vertexId, indexId);
            bindTaskCull(meshPool.meshletBounds());

            buffers.bindForDraw();
            GlBufferType.DRAW_INDIRECT_BUFFER.bind(commandBuffer);
            if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                GlBindlessTable.bind();
            }

            int lastProgram = 0;
            int baseDrawLoc = -1;
            Identifier lastTexture = null;
            GpuSampler lastSampler = null;
            for (var run : meshMultiDraws) {
                Material material = run.material();
                var key = MeshProgramKey.of(run.type(), material, run.embedded(), FrameUniforms.debugMode());
                var versions = meshPrograms.computeIfAbsent(key, _ -> new ProgramVersions<>(GlProgramBuild.TRACKER));
                GlProgramBuild build = versions.select(ProgramGeneration.current().materials(), key,
                        MeshVisualDrawManager::requestMeshProgram);
                if (build == null) {
                    continue;
                }
                int program = build.handle();
                applyMaterialState(material);
                if (program != lastProgram) {
                    lastProgram = program;
                    GlStateTracker.useProgram(program);
                    configureMainDepth(program);
                    baseDrawLoc = GL20C.glGetUniformLocation(program, "_flw_baseDraw");
                }
                if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                    Identifier texture = material.texture();
                    if (!texture.equals(lastTexture)) {
                        lastTexture = texture;
                        bindTextureCoherent(UNIT_ATLAS, textureManager.getTexture(texture)
                                                                      .getTextureView());
                    }
                    GpuSampler sampler = MaterialSamplers.get(material);
                    if (sampler != lastSampler) {
                        lastSampler = sampler;
                        GL33C.glBindSampler(UNIT_ATLAS, ((GlSampler) sampler).getId());
                    }
                }
                drawCovered(run, versions.generation(), baseDrawLoc);
            }
            setupOpaqueState();
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
            GlStateTracker.useProgram(0);
        }
    }

    @Override
    void submitOitProducerGeometry(RenderPass _, OitMode mode, OitFrame f, boolean additive) {
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }
        if (commandBuffer == 0 || builtDraws == 0) {
            return;
        }
        bindMainDepth();

        TextureManager textureManager = f.textureManager();
        int vertexId = ((GlBuffer) vertexBuffer).handle();
        int indexId = ((GlBuffer) indexBuffer).handle();
        boolean depthRange = mode == OitMode.DEPTH_RANGE;

        ensureSamplers();
        uploadModelViewUbo(renderModelView);

        GpuBufferSlice projSlice = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice lightSlice = RenderSystem.getShaderLights();
        GpuBufferSlice fogSlice = RenderSystem.getShaderFog();
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(renderModelView));

        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(false);
        // 26.3: GL_BLEND per draw buffer (glEnablei); 26.2 toggles it globally.
        for (int i = 0; i < (mode == OitMode.GENERATE_COEFFICIENTS ? 4 : 1); i++) {
            GlStateManager._enableBlend(i);
        }
        GlStateManager._blendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);
        int equation = depthRange ? GL14C.GL_MAX : GL14C.GL_FUNC_ADD;
        GlStateManager._blendEquationSeparate(equation, equation);

        if (mode == OitMode.GENERATE_COEFFICIENTS) {
            GL20C.glDrawBuffers(
                    new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1, GL30C.GL_COLOR_ATTACHMENT2, GL30C.GL_COLOR_ATTACHMENT3});
        } else {
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        }

        bindSharedFrameUbos(projSlice, lightSlice, fogSlice, dynamicTransforms,
                renderPassUniforms.renderOriginSlice(), vertexId, indexId);
        bindTaskCull(meshPool.meshletBounds());

        GL33C.glBindSampler(UNIT_ATLAS, atlasSampler);
        if (!depthRange) {
            Minecraft mc = Minecraft.getInstance();
            bindTextureCoherent(UNIT_LIGHTMAP, mc.gameRenderer.lightmap());
            bindTextureCoherent(UNIT_OVERLAY, mc.gameRenderer.overlayTexture()
                                                             .getTextureView());
            bindTextureCoherent(UNIT_OIT_DEPTH_RANGE, oitChain.framebuffer().depthBoundsView());
            bindTextureCoherent(UNIT_OIT_BLUE_NOISE, NoiseTextures.BLUE_NOISE.getTextureView());
            GL33C.glBindSampler(UNIT_LIGHTMAP, lightmapSampler);
            GL33C.glBindSampler(UNIT_OVERLAY, 0);
            GL33C.glBindSampler(UNIT_OIT_DEPTH_RANGE, 0);
            GL33C.glBindSampler(UNIT_OIT_BLUE_NOISE, 0);
            if (mode == OitMode.EVALUATE) {
                if (OitConfig.coefficientArray()) {
                    oitChain.framebuffer().bindCoefficientsArrayRaw();
                } else {
                    for (int i = 0; i < 4; i++) {
                        bindTextureCoherent(UNIT_OIT_COEFF0 + i, oitChain.framebuffer().coefficientsView(i));
                        GL33C.glBindSampler(UNIT_OIT_COEFF0 + i, 0);
                    }
                }
            }
        }

        buffers.bindForDraw();
        GlBufferType.DRAW_INDIRECT_BUFFER.bind(commandBuffer);
        if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
            GlBindlessTable.bind();
        }

        int lastProgram = 0;
        int baseDrawLoc = -1;
        for (var run : additive ? meshOitAdditiveMultiDraws : meshOitMultiDraws) {
            var key = new OitProgramKey(MeshProgramKey.of(run.type(), run.material(), run.embedded(),
                    FrameUniforms.debugMode()), additive);
            var versions = oitPrograms.computeIfAbsent(key, _ -> new ProgramVersions<>(GlProgramBuild.GROUP));
            GlProgramBuild[] group = versions.select(ProgramGeneration.current().materials(), key,
                    MeshVisualDrawManager::requestOitPrograms);
            if (group == null) {
                continue;
            }
            int program = group[additive ? 0 : mode.ordinal() - 1].handle();
            applyOitMaterialState(run.material());
            if (program != lastProgram) {
                lastProgram = program;
                GlStateTracker.useProgram(program);
                configureMainDepth(program);
                baseDrawLoc = GL20C.glGetUniformLocation(program, "_flw_baseDraw");
            }
            if (!depthRange && !GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                bindTextureCoherent(UNIT_ATLAS, textureManager.getTexture(run.material()
                                                                             .texture())
                                                              .getTextureView());
            }
            drawCovered(run, versions.generation(), baseDrawLoc);
        }

        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateTracker.useProgram(0);
        GlStateManager._blendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GlStateManager._disablePolygonOffset();
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
        var byType = doCrumblingSort(crumblingBlocks, IndirectInstancer::fromState);
        if (byType.isEmpty()) {
            return;
        }
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }
        int vertexId = ((GlBuffer) vertexBuffer).handle();
        int indexId = ((GlBuffer) indexBuffer).handle();

        ensureSamplers();
        uploadModelViewUbo(renderModelView);

        GpuBufferSlice projSlice = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice lightSlice = RenderSystem.getShaderLights();
        GpuBufferSlice fogSlice = RenderSystem.getShaderFog();
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(renderModelView));

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();
        TextureManager tm = mc.getTextureManager();
        var crumblingMat = SimpleMaterial.builder();

        CommandEncoder encoder = RenderSystem.getDevice()
                                             .createCommandEncoder();

        GlCompat.pushDebugGroup("flywheel:gl/crumbling");
        try (RenderPass _ = encoder.createRenderPass(() -> "flywheel:meshvisual/crumbling",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            // Positive offset under reversed-Z: toward the camera, wins the z-fight with the coplanar block.
            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(GL11C.GL_DST_COLOR, GL11C.GL_SRC_COLOR, GL11C.GL_ONE, GL11C.GL_ZERO);
            GlStateManager._enableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._polygonOffset(1.0f, 10.0f);
            GlStateManager._enablePolygonOffset();

            bindTextureCoherent(UNIT_LIGHTMAP, lightmapView);
            bindTextureCoherent(UNIT_OVERLAY, overlayView);
            GL33C.glBindSampler(UNIT_ATLAS, atlasSampler);
            GL33C.glBindSampler(UNIT_OVERLAY, 0);
            GL33C.glBindSampler(UNIT_LIGHTMAP, lightmapSampler);
            GL33C.glBindSampler(UNIT_CRACK, crackSampler);

            bindSharedFrameUbos(projSlice, lightSlice, fogSlice, dynamicTransforms,
                    renderPassUniforms.renderOriginSlice(), vertexId, indexId);

            buffers.bindForCrumbling();
            drawBarrier();
            for (var groupEntry : byType.entrySet()) {
                InstanceType<?> instanceType = groupEntry.getKey()
                                                         .instanceType();
                var key = CrumblingProgramKey.of(instanceType, FrameUniforms.debugMode());
                GlProgramBuild build = crumblingPrograms.computeIfAbsent(key,
                        _ -> new ProgramVersions<>(GlProgramBuild.TRACKER)).select(ProgramGeneration.NONE, key,
                        MeshVisualDrawManager::requestCrumblingProgram);
                if (build == null) {
                    continue;
                }
                int program = build.handle();
                for (var progressEntry : groupEntry.getValue()
                                                   .int2ObjectEntrySet()) {
                    GpuTextureView crackView = tm.getTexture(
                                                         ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey()))
                                                 .getTextureView();
                    for (var pair : progressEntry.getValue()) {
                        IndirectInstancer<?> instancer = pair.getFirst();
                        int objectSlot = instancer.local2ObjectUintOffset(pair.getSecond().index);
                        for (IndirectDraw draw : instancer.draws()) {
                            var mesh = draw.mesh();
                            if (mesh.isInvalid()) {
                                continue;
                            }
                            CommonCrumbling.applyCrumblingProperties(crumblingMat, draw.material());
                            GlStateManager._depthFunc(depthFunc(crumblingMat.depthTest()));
                            if (crumblingMat.backfaceCulling()) {
                                GlStateManager._enableCull();
                            } else {
                                GlStateManager._disableCull();
                            }
                            GpuTextureView atlasView = tm.getTexture(draw.material()
                                                                         .texture())
                                                         .getTextureView();
                            drawCrumblingMesh(program, objectSlot, mesh.firstIndex(),
                                    mesh.baseVertex(), mesh.indexCount() / 3,
                                    MaterialEncoder.packProperties(crumblingMat), atlasView, crackView);
                        }
                    }
                }
            }

            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
            GlStateTracker.useProgram(0);
            GlStateManager._disablePolygonOffset();
            GlStateManager._disableBlend(0);
            GlStateManager._depthMask(true);
        }
        GlCompat.popDebugGroup();
    }

    private void drawCrumblingMesh(int program, int objectSlot, int firstIndex, int vertexOffset, int triCount,
                                   int packedMaterial, GpuTextureView atlasView, GpuTextureView crackView) {
        if (triCount == 0) {
            return;
        }
        bindTextureCoherent(UNIT_ATLAS, atlasView);
        bindTextureCoherent(UNIT_CRACK, crackView);
        GlStateTracker.useProgram(program);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(program, "_flw_crumblingInstance"), objectSlot);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(program, "_flw_crumblingFirstIndex"), firstIndex);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(program, "_flw_crumblingVertexOffset"), vertexOffset);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(program, "_flw_crumblingTriCount"), triCount);
        GL30C.glUniform1ui(GL20C.glGetUniformLocation(program, "_flw_crumblingPackedMaterial"), packedMaterial);
        NVMeshShader.glDrawMeshTasksNV(0, (triCount + 63) / 64);
    }

    // The run's draws its program generation and the frame's cull program cover.
    private void drawCovered(MeshDrawRun run, ProgramGeneration generation, int baseDrawLoc) {
        ProgramGeneration coverage = generation.culledBy(cullTypes());
        if (coverage.covers(run.required())) {
            drawMeshTasks(run.start(), run.end(), baseDrawLoc);
            return;
        }
        int start = run.start();
        for (int i = run.start(); i < run.end(); i++) {
            if (coverage.covers(draw(i).required())) continue;
            if (start < i) drawMeshTasks(start, i, baseDrawLoc);
            start = i + 1;
        }
        if (start < run.end()) drawMeshTasks(start, run.end(), baseDrawLoc);
    }

    private static void drawMeshTasks(int start, int end, int baseDrawLoc) {
        if (baseDrawLoc >= 0) {
            GL30C.glUniform1ui(baseDrawLoc, start);
        }
        NVMeshShader.glMultiDrawMeshTasksIndirectNV((long) start * MESH_TASK_COMMAND_STRIDE, end - start,
                MESH_TASK_COMMAND_STRIDE);
    }

    private static GlProgramBuild requestMeshProgram(MeshProgramKey key) {
        return request(meshStages(key), MeshVisualDrawManager::meshSetup);
    }

    private static GlProgramBuild requestCrumblingProgram(CrumblingProgramKey key) {
        return request(crumblingStages(key), MeshVisualDrawManager::crumblingSetup);
    }

    private static @Nullable GlProgramBuild[] requestOitPrograms(OitProgramKey key) {
        if (key.emission()) {
            return new GlProgramBuild[]{request(oitStages(key.key(), OitMode.EVALUATE, true),
                    MeshVisualDrawManager::oitSetup)};
        }
        GlProgramBuild[] group = new GlProgramBuild[OitMode.values().length - 1];
        for (OitMode mode : OitMode.values()) {
            if (mode != OitMode.OFF) {
                group[mode.ordinal() - 1] = request(oitStages(key.key(), mode, false), MeshVisualDrawManager::oitSetup);
            }
        }
        return group;
    }

    private int ensureCommandBuilder() {
        if (commandBuilderProgram != -1) {
            return commandBuilderProgram;
        }
        int program = buildCommandBuilder();
        if (program != 0) {
            commandBuilderDrawCountLoc = GL20C.glGetUniformLocation(program, "_flw_drawCount");
            commandBuilderWriteOffsetLoc = GL20C.glGetUniformLocation(program, "_flw_writeOffset");
        }
        commandBuilderProgram = program;
        return program;
    }

    private void uploadModelViewUbo(Matrix4fc modelView) {
        ensureMatrixUbo();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer scratch = stack.malloc((int) MeshVisualShaders.FRAME_UBO_BYTES);
            modelView.get(0, scratch);
            scratch.putFloat(64, (float) ((double) (System.nanoTime() / 1000000L) / 1000.0));
            scratch.putFloat(68, Minecraft.getInstance().options.glintSpeed()
                                                                .get()
                                                                .floatValue());
            scratch.putFloat(72, Minecraft.getInstance().options.glintStrength()
                                                                .get()
                                                                .floatValue());
            scratch.putFloat(76, FrameUniforms.partialTick());
            scratch.putFloat(80, FrameUniforms.renderTicks());
            scratch.putFloat(84, FrameUniforms.renderSeconds());
            scratch.putInt(88, FrameUniforms.gameMillis());
            GL45C.glNamedBufferSubData(matrixUbo, 0L, scratch);
        }
    }

    private void bindSharedFrameUbos(GpuBufferSlice projSlice, GpuBufferSlice lightSlice, GpuBufferSlice fogSlice,
                                     GpuBufferSlice dynamicTransforms, GpuBufferSlice renderOrigin,
                                     int vertexId, int indexId) {
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, 9, matrixUbo);
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, 7, ((GlBuffer) projSlice.buffer()).handle(),
                projSlice.offset(), projSlice.length());
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, 6, ((GlBuffer) lightSlice.buffer()).handle(),
                lightSlice.offset(), lightSlice.length());
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, 4, ((GlBuffer) renderOrigin.buffer()).handle(),
                renderOrigin.offset(), renderOrigin.length());
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, UBO_FOG, ((GlBuffer) fogSlice.buffer()).handle(),
                fogSlice.offset(), fogSlice.length());
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, UBO_DYNAMIC_TRANSFORMS,
                ((GlBuffer) dynamicTransforms.buffer()).handle(), dynamicTransforms.offset(),
                dynamicTransforms.length());
        lightBuffers.bind();
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SSBO_MESH_VERTICES, vertexId);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SSBO_MESH_INDICES, indexId);
    }

    private void ensureCommandBuffer(int draws) {
        if (commandBuffer != 0 && commandBufferCapacity >= draws) {
            return;
        }
        if (commandBuffer != 0) {
            GL15C.glDeleteBuffers(commandBuffer);
            FlwMemoryTracker._freeGpuMemory((long) commandBufferCapacity * MESH_TASK_COMMAND_STRIDE);
        }
        int capacity = Math.max(draws, 64);
        commandBuffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(commandBuffer, (long) capacity * MESH_TASK_COMMAND_STRIDE, GL15C.GL_DYNAMIC_DRAW);
        FlwMemoryTracker._allocGpuMemory((long) capacity * MESH_TASK_COMMAND_STRIDE);
        commandBufferCapacity = capacity;
    }

    private void ensureMatrixUbo() {
        if (matrixUbo != 0) {
            return;
        }
        matrixUbo = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(matrixUbo, MeshVisualShaders.FRAME_UBO_BYTES, GL15C.GL_DYNAMIC_DRAW);
        FlwMemoryTracker._allocGpuMemory(MeshVisualShaders.FRAME_UBO_BYTES);
    }

    // Atlas: NEAREST mag + mipmapped min (the vanilla-block look; sampler-object 0 inherited LINEAR = mush).
    // Lightmap: LINEAR.
    private void ensureSamplers() {
        if (atlasSampler != 0) {
            return;
        }
        atlasSampler = GL33C.glGenSamplers();
        GL33C.glSamplerParameteri(atlasSampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL33C.glSamplerParameteri(atlasSampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_LINEAR);
        GL33C.glSamplerParameteri(atlasSampler, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL33C.glSamplerParameteri(atlasSampler, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

        lightmapSampler = GL33C.glGenSamplers();
        GL33C.glSamplerParameteri(lightmapSampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL33C.glSamplerParameteri(lightmapSampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL33C.glSamplerParameteri(lightmapSampler, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL33C.glSamplerParameteri(lightmapSampler, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

        crackSampler = GL33C.glGenSamplers();
        GL33C.glSamplerParameteri(crackSampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL33C.glSamplerParameteri(crackSampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL33C.glSamplerParameteri(crackSampler, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL33C.glSamplerParameteri(crackSampler, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
    }

    @Override
    public void delete() {
        super.delete();
        meshPrograms.values().forEach(ProgramVersions::release);
        meshPrograms.clear();
        crumblingPrograms.values().forEach(ProgramVersions::release);
        crumblingPrograms.clear();
        oitPrograms.values().forEach(ProgramVersions::release);
        oitPrograms.clear();
        if (commandBuilderProgram > 0) {
            GL20C.glDeleteProgram(commandBuilderProgram);
        }
        commandBuilderProgram = -1;
        if (commandBuffer != 0) {
            GL15C.glDeleteBuffers(commandBuffer);
            FlwMemoryTracker._freeGpuMemory((long) commandBufferCapacity * MESH_TASK_COMMAND_STRIDE);
            commandBuffer = 0;
            commandBufferCapacity = 0;
        }
        if (matrixUbo != 0) {
            GL15C.glDeleteBuffers(matrixUbo);
            FlwMemoryTracker._freeGpuMemory(MeshVisualShaders.FRAME_UBO_BYTES);
            matrixUbo = 0;
        }
        if (atlasSampler != 0) {
            GL33C.glDeleteSamplers(atlasSampler);
            atlasSampler = 0;
        }
        if (lightmapSampler != 0) {
            GL33C.glDeleteSamplers(lightmapSampler);
            lightmapSampler = 0;
        }
        if (crackSampler != 0) {
            GL33C.glDeleteSamplers(crackSampler);
            crackSampler = 0;
        }
    }

    private record MeshProgramKey(InstanceType<?> type, MaterialShaders shaders, LightShader light, DebugMode debug,
                                  boolean embedded, LightSmoothness smoothness) {
        static MeshProgramKey of(InstanceType<?> type, Material material, boolean embedded, DebugMode debug) {
            return new MeshProgramKey(type, material.shaders(), material.light(), debug, embedded,
                    BackendConfig.INSTANCE.lightSmoothness());
        }
    }

    /**
     * {@code emission}: the additive pass's evaluate program alone; otherwise one program per wavelet mode.
     */
    private record OitProgramKey(MeshProgramKey key, boolean emission) {
    }

    private record Stages(String label, int[] types, String[] stageLabels, String[] sources) {
    }

    private record CrumblingProgramKey(InstanceType<?> type, DebugMode debug, LightSmoothness smoothness) {
        static CrumblingProgramKey of(InstanceType<?> type, DebugMode debug) {
            return new CrumblingProgramKey(type, debug, BackendConfig.INSTANCE.lightSmoothness());
        }
    }
}
