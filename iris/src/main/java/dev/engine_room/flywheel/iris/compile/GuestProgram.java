package dev.engine_room.flywheel.iris.compile;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.iris.mixin.CustomUniformsAccessor;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
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
 * Iris runs {@link #iris$setupState} from the {@code trySetup} tail of every pass that selects a guest pipeline.
 */
public final class GuestProgram extends GlProgram implements IrisProgram {
    // Iris binds its external samplers (albedo/overlay/lightmap) to these units by name.
    private static final String[] FIXED_SAMPLERS = {"Sampler0", "Sampler1", "Sampler2"};
    private static final Map<String, String> IRIS_BLOCKS = Map.of(
            "DynamicTransforms", "iris_DynamicTransforms",
            "Projection", "iris_Projection",
            "Globals", "iris_Globals",
            "Fog", "iris_Fog");

    private static final Matrix4f MODEL_VIEW = new Matrix4f();

    private final IrisRenderingPipeline parent;
    private final boolean shadow;
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
    // Bindings a guest draw owns that Iris knows nothing about (terrain's Sodium u_Globals block).
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

    /**
     * {@code rawTextures}: textures outside Blaze3D's bind groups, bound here on units after the layout's.
     */
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

        rawTextureBaseUnit = bindLayouts(layouts, albedoFromCrumbling);
        int reservedUnits = rawTextureBaseUnit + rawTextures.size();

        ImmutableSet.Builder<Integer> reserved = ImmutableSet.builder();
        for (int unit = 0; unit < reservedUnits; unit++) {
            reserved.add(unit);
        }

        ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(label, programId);
        ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(programId, reserved.build());
        ProgramImages.Builder imageBuilder = ProgramImages.builder(programId);
        CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
        parent.getCustomUniforms()
              .assignTo(uniformBuilder);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
        VanillaUniforms.addVanillaUniforms(uniformBuilder);
        Supplier<ImmutableSet<Integer>> flipped = shadow ? parent::getFlippedBeforeShadow
                : () -> parent.isBeforeTranslucent ? parent.getFlippedAfterPrepare() : parent.getFlippedAfterTranslucent();
        parent.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, flipped, shadow, true, true, true);
        parent.getCustomUniforms()
              .mapholderToPass(uniformBuilder, this);

        uniforms = uniformBuilder.buildUniforms();
        samplers = samplerBuilder.build();
        images = imageBuilder.build();

        modelViewInverse = GlStateManager._glGetUniformLocation(programId, "iris_ModelViewMatInverse");
        normalMatrix = GlStateManager._glGetUniformLocation(programId, "iris_NormalMat");
        projectionInverse = GlStateManager._glGetUniformLocation(programId, "iris_ProjMatInverse");
    }

    /**
     * The model view the guest draw writes to {@code DynamicTransforms}; the Iris inverse/normal uniforms derive
     * from it instead of {@code RenderSystem}'s stack.
     */
    public static void setModelView(Matrix4fc modelView) {
        MODEL_VIEW.set(modelView);
    }

    /**
     * Samplers take units 0..2 then the rest, texel buffers follow; every layout entry is registered even when
     * the linked program dropped it, since the draw loop reads its unit/binding by name. Returns the unit count.
     */
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
                // The pack's damagedblock program samples its albedo (unit 0) for the crack.
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

    @Override
    public void iris$setupState(HashMap<String, GlRenderPass.TextureViewAndSampler> passSamplers,
                                @Nullable GpuTextureView albedoTex) {
        DepthColorStorage.unlockDepthColor();
        CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alphaReference);
        GlStateManager._glUseProgram(getProgramId());
        if (terrain) {
            GuestSsbos.bindForGuestTerrain(parent);
            GL20C.glUniform3i(terrainCameraInt, GuestTerrainGate.cameraIntX, GuestTerrainGate.cameraIntY,
                    GuestTerrainGate.cameraIntZ);
            GL20C.glUniform3f(terrainCameraFrac, GuestTerrainGate.cameraFracX, GuestTerrainGate.cameraFracY,
                    GuestTerrainGate.cameraFracZ);
            // Set before deriving inverse/normal matrices; an instance draw's model view is not terrain's.
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
            Matrix4fc projection = shadow ? MatrixUtils.undoRevZ(ShadowRenderer.PROJECTION)
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
    }

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
                // Untracked target: GlStateManager caches TEXTURE_2D only.
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

    public int meshQuads() {
        return meshQuads;
    }

    /**
     * Source quads per task workgroup, or per mesh workgroup when no task shader is linked.
     */
    public int meshTaskQuads() {
        return meshTaskQuads;
    }

    /**
     * Whether opaque phase one can reject on carried depth and therefore needs immediate terrain-phase recovery.
     */
    public boolean meshTaskRecovery() {
        return meshTaskRecovery;
    }

    /**
     * Empty-draw removal changes gl_DrawID even though the surviving command order is preserved.
     */
    public boolean meshCompactSafe() {
        return meshCompactSafe;
    }

    /**
     * The terrain companion sets these on the render thread immediately before its RenderPass draw. Each four-int
     * run contains first command, capacity, count-buffer word offset, padding. The arrays remain live until that draw.
     */
    public void meshCommands(int commands, int counts, int[] runs, int runCount) {
        meshCommandBuffer = commands;
        meshCountBuffer = counts;
        meshRuns = runs;
        meshRunCount = runCount;
    }

    /**
     * Consumed by the GL encoder's draw seam, after ordinary RenderPass and Iris state setup.
     */
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
        // Iris retains dimension pipelines; closed guest variants must not remain keys in their custom uniforms.
        ((CustomUniformsAccessor) parent.getCustomUniforms()).flywheel$locations().remove(this);
        super.close();
    }

    @Override
    public void iris$clearState() {
        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();
        BlendModeOverride.restore();
    }

    @Override
    public int iris$getBlockIndex(int program, CharSequence uniformBlockName) {
        String name = uniformBlockName.toString();
        return GL31C.glGetUniformBlockIndex(program, IRIS_BLOCKS.getOrDefault(name, name));
    }

    // Always false: Iris skips setup for a set-up program, which would leave the previous pipeline's framebuffer.
    @Override
    public boolean iris$isSetUp() {
        return false;
    }

    interface Target {
        void bind(boolean beforeTranslucent);
    }

    /**
     * Sampled with {@code texelFetch} only: no sampler object state applies.
     */
    record RawTexture(String name, int glTarget, IntSupplier texture) {
    }
}
