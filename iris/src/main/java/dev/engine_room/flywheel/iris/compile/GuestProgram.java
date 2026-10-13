package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.iris.mixin.CustomUniformsAccessor;
import com.google.common.collect.ImmutableSet;
#if MC < 26.3
import com.mojang.blaze3d.opengl.GlRenderPass.TextureViewAndSampler;
#endif
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
#if MC >= 26.3
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.util.TextureViewAndSampler;
#else
import com.mojang.blaze3d.opengl.GlProgram;
#endif
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.Uniform;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
#if MC >= 26.3
import net.irisshaders.iris.mixinterface.GlProgramBindings;
#endif
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.IrisProgram;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.VanillaUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Iris invokes {@link #iris$setupState} when installing a guest program for a native draw.
 */
public final class GuestProgram extends GlProgram implements IrisProgram #if MC >= 26.3 , GlProgramBindings #endif {
    // Iris binds its external samplers (albedo/overlay/lightmap) to these units by name.
    private static final String[] FIXED_SAMPLERS = {"Sampler0", "Sampler1", "Sampler2"};
    private static final int[] GRAYSCALE_SWIZZLE = {GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_RED};
    private static final Map<String, String> IRIS_BLOCKS = Map.of(
            "DynamicTransforms", "iris_DynamicTransforms",
            "Projection", "iris_Projection",
            "Globals", "iris_Globals",
            "Fog", "iris_Fog");

    private static final Matrix4f MODEL_VIEW = new Matrix4f();

    private final IrisRenderingPipeline parent;
    private final boolean shadow;
    #if MC >= 26.3
    private final Map<String, Uniform> namedUniforms = new HashMap<>();
    private final List<BindGroupLayout.UniformDescription> uniformDescriptions;
    private final int albedoUniformIndex;
    #endif
    private final ProgramUniforms uniforms;
    private final ProgramSamplers samplers;
    private final ProgramImages images;
    private final Target target;
    private final List<RawTexture> rawTextures;
    private final int rawTextureBaseUnit;
    private final @Nullable BlendModeOverride blendModeOverride;
    private final List<BufferBlendOverride> bufferBlendOverrides;
    private final float alphaReference;
    private final int modelViewInverse;
    private final int normalMatrix;
    private final int projectionInverse;
    private final Matrix4f scratch = new Matrix4f();
    private final Matrix3f scratch3 = new Matrix3f();
    private final float[] floats16 = new float[16];
    private final float[] floats9 = new float[9];
    private boolean rawSamplersAssigned;
    private boolean grayscaleAlbedo;
    private boolean terrain;
    private int meshQuads;
    private int meshTaskQuads;
    private boolean meshTaskRecovery;
    private boolean meshCompactSafe;
    private int terrainCameraInt;
    private int terrainCameraFrac;
    private int meshCommandBaseLocation;
    private int meshCommandBuffer;
    private int meshCountBuffer;
    private int[] meshRuns;
    private int meshRunCount;

    GuestProgram(int programId, String label, List<BindGroupLayout> layouts, boolean albedoFromCrumbling,
                 IrisRenderingPipeline parent, boolean shadow, Target target,
                 @Nullable BlendModeOverride blendModeOverride, List<BufferBlendOverride> bufferBlendOverrides,
                 AlphaTest alphaTest, List<RawTexture> rawTextures) {
        super(programId, label);
        this.parent = parent;
        this.shadow = shadow;
        this.target = target;
        this.rawTextures = rawTextures;
        this.blendModeOverride = blendModeOverride;
        this.bufferBlendOverrides = bufferBlendOverrides;
        this.alphaReference = alphaTest.reference();

        #if MC >= 26.3
        uniformDescriptions = List.copyOf(BindGroupLayout.flattenUniforms(layouts));
        int albedoIndex = -1;
        for (int index = 0; index < uniformDescriptions.size(); index++) {
            if (uniformDescriptions.get(index).name().equals("Sampler0")) {
                albedoIndex = index;
                break;
            }
        }
        albedoUniformIndex = albedoIndex;
        #endif
        rawTextureBaseUnit = bindLayouts(layouts, albedoFromCrumbling);
        int reservedUnits = rawTextureBaseUnit + rawTextures.size();

        ImmutableSet.Builder<Integer> reserved = ImmutableSet.builder();
        for (int unit = 0; unit < reservedUnits; unit++) {
            reserved.add(unit);
        }

        ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(label, programId);
        boolean portable = GlCompat.MAX_GLSL_VERSION.compareTo(GlslVersion.V460) < 0;
        int remainingUnits = portable ? SamplerLimits.get().getMaxTextureUnits() - reservedUnits : 0;
        if (portable && remainingUnits < 0) {
            throw new BackendUnavailableException(label + " reserves " + reservedUnits + " texture units; available "
                    + SamplerLimits.get().getMaxTextureUnits());
        }
        ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(programId, reserved.build());
        SamplerHolder samplerHolder = portable ? new GuestSamplers(samplerBuilder, remainingUnits, label) : samplerBuilder;
        ProgramImages.Builder imageBuilder = ProgramImages.builder(programId);
        CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
        parent.getCustomUniforms()
              .assignTo(uniformBuilder);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
        VanillaUniforms.addVanillaUniforms(uniformBuilder);
        Supplier<ImmutableSet<Integer>> flipped = shadow ? parent::getFlippedBeforeShadow
                : () -> parent.isBeforeTranslucent ? parent.getFlippedAfterPrepare() : parent.getFlippedAfterTranslucent();
        parent.addGbufferOrShadowSamplers(samplerHolder, imageBuilder, flipped, shadow, true, true, true);
        parent.getCustomUniforms()
              .mapholderToPass(uniformBuilder, this);

        uniforms = uniformBuilder.buildUniforms();
        samplers = samplerBuilder.build();
        images = imageBuilder.build();

        modelViewInverse = GlStateManager._glGetUniformLocation(programId, "iris_ModelViewMatInverse");
        normalMatrix = GlStateManager._glGetUniformLocation(programId, "iris_NormalMat");
        projectionInverse = GlStateManager._glGetUniformLocation(programId, "iris_ProjMatInverse");
    }

    /** Before guest draw; supply DynamicTransforms model view for Iris inverse/normal uniforms. */
    public static void setModelView(Matrix4fc modelView) {
        MODEL_VIEW.set(modelView);
    }

    static void grayscaleAlbedo(GpuTextureView albedo) {
        int texture = ((GlTexture) albedo.texture()).glId();
        IrisRenderSystem.addUnswizzle(texture);
        IrisRenderSystem.texParameteriv(texture, GL11C.GL_TEXTURE_2D, ARBTextureSwizzle.GL_TEXTURE_SWIZZLE_RGBA,
                GRAYSCALE_SWIZZLE);
    }

    /**
     * Samplers take units 0..2 then the rest, texel buffers follow; every layout entry registers even when the linked
     * program dropped it: the draw loop addresses entries by name (26.2) / flattened layout index (26.3). Returns the
     * unit count.
     */
    #if MC >= 26.3
    private int bindLayouts(List<BindGroupLayout> layouts, boolean albedoFromCrumbling) {
        int programId = getProgramId();
        GlStateManager._glUseProgram(programId);
        List<String> samplerNames = uniformDescriptions.stream()
                .filter(description -> description.type() == UniformType.COMBINED_IMAGE_SAMPLER)
                .map(BindGroupLayout.UniformDescription::name).toList();
        int nextUnit = 0;
        for (String fixed : FIXED_SAMPLERS) {
            int unit = nextUnit++;
            if (albedoFromCrumbling && fixed.equals("Sampler0")) continue;
            if (samplerNames.contains(fixed)) {
                assignSampler(programId, fixed, unit);
                namedUniforms.put(fixed, new Uniform.Sampler(unit));
            }
        }
        for (String sampler : samplerNames) {
            if (namedUniforms.containsKey(sampler) || List.of(FIXED_SAMPLERS).contains(sampler)) continue;
            int unit = albedoFromCrumbling && sampler.equals("_flw_crumblingTex") ? 0 : nextUnit++;
            assignSampler(programId, albedoFromCrumbling && sampler.equals("_flw_crumblingTex") ? "Sampler0" : sampler,
                    unit);
            namedUniforms.put(sampler, new Uniform.Sampler(unit));
        }
        int nextUbo = 0;
        for (BindGroupLayout.UniformDescription description : uniformDescriptions) {
            String name = description.name();
            Uniform uniform;
            if (description.type() == UniformType.COMBINED_IMAGE_SAMPLER) {
                uniform = namedUniforms.get(name);
            } else if (description.type() == UniformType.UNIFORM_BUFFER) {
                int binding = nextUbo++;
                int index = iris$getBlockIndex(programId, name);
                if (index != GL31C.GL_INVALID_INDEX) GL31C.glUniformBlockBinding(programId, index, binding);
                uniform = new Uniform.Ubo(binding);
                namedUniforms.put(name, uniform);
            } else {
                assignSampler(programId, name, nextUnit);
                uniform = new Uniform.Utb(nextUnit++, description.gpuFormat());
                namedUniforms.put(name, uniform);
            }
            super.uniforms.add(uniform);
        }
        GlStateManager._glUseProgram(0);
        return nextUnit;
    }

    private static void assignSampler(int program, String name, int unit) {
        GlStateManager._glUniform1i(GlStateManager._glGetUniformLocation(program, name), unit);
    }

    @Override
    public void iris$setupBindings(List<BindGroupLayout.UniformDescription> descriptions, int pushConstantsSize) {
        // Compat with Iris: bindings are installed once at link time; each sharing pipeline is checked at registration.
        // Iris's vanilla binding policy would replace the engine's UBOs and texel buffers with null entries.
        if (pushConstantsSize != 0 || descriptions.size() != uniformDescriptions.size()) {
            throw new IllegalStateException("Guest pipeline changed its registered binding contract: "
                    + getDebugLabel());
        }
    }

    Map<String, Uniform> namedUniforms(List<BindGroupLayout> layouts) {
        if (!uniformDescriptions.equals(BindGroupLayout.flattenUniforms(layouts))) {
            throw new IllegalStateException("Guest program reused with a different uniform layout: " + getDebugLabel());
        }
        return namedUniforms;
    }
    #else
    private int bindLayouts(List<BindGroupLayout> layouts, boolean albedoFromCrumbling) {
        Map<String, Uniform> byName = uniformsByName;
        int programId = getProgramId();
        List<String> samplerNames = BindGroupLayout.flattenSamplers(layouts);
        int nextUnit = 0;
        for (String fixed : FIXED_SAMPLERS) {
            int unit = nextUnit++;
            if (albedoFromCrumbling && fixed.equals("Sampler0")) {
                continue;
            }
            if (samplerNames.contains(fixed)) {
                byName.put(fixed, new Uniform.Sampler(GlStateManager._glGetUniformLocation(programId, fixed), unit));
            }
        }
        for (String sampler : samplerNames) {
            if (byName.containsKey(sampler) || List.of(FIXED_SAMPLERS).contains(sampler)) {
                continue;
            }
            if (albedoFromCrumbling && sampler.equals("_flw_crumblingTex")) {
                byName.put(sampler,
                        new Uniform.Sampler(GlStateManager._glGetUniformLocation(programId, "Sampler0"), 0));
                continue;
            }
            byName.put(sampler,
                    new Uniform.Sampler(GlStateManager._glGetUniformLocation(programId, sampler), nextUnit++));
        }

        int nextUboBinding = 0;
        for (BindGroupLayout.UniformDescription description : BindGroupLayout.flattenUniforms(layouts)) {
            String name = description.name();
            if (description.type() == UniformType.UNIFORM_BUFFER) {
                int binding = nextUboBinding++;
                int index = iris$getBlockIndex(programId, name);
                if (index != GL31C.GL_INVALID_INDEX) {
                    GL31C.glUniformBlockBinding(programId, index, binding);
                }
                byName.put(name, new Uniform.Ubo(binding));
            } else {
                byName.put(name, new Uniform.Utb(GlStateManager._glGetUniformLocation(programId, name), nextUnit++,
                        description.gpuFormat()));
            }
        }
        return nextUnit;
    }
    #endif

    @Override
    #if MC >= 26.3
    public void iris$setupState(List<BindGroupLayout.UniformDescription> descriptions) {
    #else
    public void iris$setupState(HashMap<String, TextureViewAndSampler> passSamplers,
                                @Nullable GpuTextureView albedoTex) {
    #endif
        DepthColorStorage.unlockDepthColor();
        CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alphaReference);
        GlStateManager._glUseProgram(getProgramId());
        if (terrain) {
            GuestSsbos.bindForGuestTerrain(parent);
            GL20C.glUniform3i(terrainCameraInt, GuestTerrainGate.cameraIntX, GuestTerrainGate.cameraIntY,
                    GuestTerrainGate.cameraIntZ);
            GL20C.glUniform3f(terrainCameraFrac, GuestTerrainGate.cameraFracX, GuestTerrainGate.cameraFracY,
                    GuestTerrainGate.cameraFracZ);
            setModelView(shadow ? ShadowRenderer.MODELVIEW : CapturedRenderingState.INSTANCE.getGbufferModelView());
        }

        MODEL_VIEW.invert(scratch);
        if (modelViewInverse > -1) {
            IrisRenderSystem.uniformMatrix4fv(modelViewInverse, false, scratch.get(floats16));
        }
        if (normalMatrix > -1) {
            IrisRenderSystem.uniformMatrix3fv(normalMatrix, false, scratch.transpose3x3(scratch3)
                                                                          .get(floats9));
        }
        if (projectionInverse > -1) {
            Matrix4fc projection = shadow ? MatrixUtils.toMinusOneToOne(ShadowRenderer.PROJECTION)
                    : CapturedRenderingState.INSTANCE.getGbufferProjection();
            IrisRenderSystem.uniformMatrix4fv(projectionInverse, false, projection.invert(scratch)
                                                                                  .get(floats16));
        }

        samplers.update();
        bindRawTextures();
        uniforms.update();
        parent.getCustomUniforms()
              .push(this);
        images.update();

        BlendModeOverride.restore();
        if (blendModeOverride != null) {
            blendModeOverride.apply();
        }
        bufferBlendOverrides.forEach(BufferBlendOverride::apply);

        target.bind(parent.isBeforeTranslucent);
        #if MC < 26.3
        if (grayscaleAlbedo) grayscaleAlbedo(albedoTex);
        #endif
    }

    void useGrayscaleAlbedo() {
        grayscaleAlbedo = true;
    }

    #if MC >= 26.3
    /** After native texture binding, before a draw; font atlases expose coverage in their red channel. */
    public void setupAlbedo(List<?> boundUniforms) {
        if (grayscaleAlbedo) {
            grayscaleAlbedo(((TextureViewAndSampler) boundUniforms.get(albedoUniformIndex)).view());
        }
    }
    #endif

    private void bindRawTextures() {
        if (rawTextures.isEmpty()) {
            return;
        }
        int programId = getProgramId();
        if (!rawSamplersAssigned) {
            rawSamplersAssigned = true;
            for (int i = 0; i < rawTextures.size(); i++) {
                GlStateManager._glUniform1i(GlStateManager._glGetUniformLocation(programId, rawTextures.get(i).name()),
                        rawTextureBaseUnit + i);
            }
        }
        int activeTexture = GlStateManagerAccessor.getActiveTexture();
        for (int i = 0; i < rawTextures.size(); i++) {
            RawTexture texture = rawTextures.get(i);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + rawTextureBaseUnit + i);
            if (texture.glTarget() == GL11C.GL_TEXTURE_2D) {
                GlStateManager._bindTexture(texture.texture().getAsInt());
            } else {
                GL11C.glBindTexture(texture.glTarget(), texture.texture().getAsInt());
            }
        }
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + activeTexture);
    }

    void useTerrainState() {
        terrain = true;
        terrainCameraInt = GL20C.glGetUniformLocation(getProgramId(), "_flw_sodiumCameraInt");
        terrainCameraFrac = GL20C.glGetUniformLocation(getProgramId(), "_flw_sodiumCameraFrac");
    }

    void useMesh(int quads, int taskQuads, boolean taskRecovery, boolean compactSafe) {
        meshQuads = quads;
        meshTaskQuads = taskQuads;
        meshTaskRecovery = taskRecovery;
        meshCompactSafe = compactSafe;
        meshCommandBaseLocation = GlStateManager._glGetUniformLocation(getProgramId(), "_flw_commandBase");
    }

    boolean shadow() {
        return shadow;
    }

    public int meshQuads() {
        return meshQuads;
    }

    /** Source quads per task group; no task stage => mesh group. */
    public int meshTaskQuads() {
        return meshTaskQuads;
    }

    /** Carried-depth rejection; requires immediate terrain-phase recovery. */
    public boolean meshTaskRecovery() {
        return meshTaskRecovery;
    }

    /** Empty-draw removal changes {@code gl_DrawID}. */
    public boolean meshCompactSafe() {
        return meshCompactSafe;
    }

    /** Before terrain draw, render thread; retain runs through draw: (first, capacity, count-word offset, pad). */
    public void meshCommands(int commands, int counts, int[] runs, int runCount) {
        meshCommandBuffer = commands;
        meshCountBuffer = counts;
        meshRuns = runs;
        meshRunCount = runCount;
    }

    /** GL draw seam after RenderPass/Iris setup. */
    public void drawMeshCommands() {
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, meshCommandBuffer);
        GL15C.glBindBuffer(GL46C.GL_PARAMETER_BUFFER, meshCountBuffer);
        for (int run = 0; run < meshRunCount; run++) {
            int index = run * 4;
            GlStateManager._glUniform1i(meshCommandBaseLocation, meshRuns[index]);
            NVMeshShader.glMultiDrawMeshTasksIndirectCountNV((long) meshRuns[index] * 20L,
                    (long) meshRuns[index + 2] * Integer.BYTES, meshRuns[index + 1], 20);
        }
    }

    @Override
    public void close() {
        ((CustomUniformsAccessor) parent.getCustomUniforms()).flywheel$locations().remove(this);
        super.close();
    }

    @Override
    public void iris$clearState() {
        if (grayscaleAlbedo) IrisRenderSystem.onProgramUse();
        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();
        BlendModeOverride.restore();
    }

    @Override
    public int iris$getBlockIndex(int program, CharSequence uniformBlockName) {
        String name = uniformBlockName.toString();
        return GL31C.glGetUniformBlockIndex(program, IRIS_BLOCKS.getOrDefault(name, name));
    }

    @Override
    public boolean iris$isSetUp() {
        return false;
    }

    interface Target {
        void bind(boolean beforeTranslucent);
    }

    /** {@code texelFetch}-only; no sampler state. */
    record RawTexture(String name, int glTarget, IntSupplier texture) {
    }
}
