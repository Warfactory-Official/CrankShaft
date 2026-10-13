package dev.engine_room.flywheel.impl.compat;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.compat.backend.RenderPassOps;
import dev.engine_room.flywheel.compat.backend.UniformData;
import dev.engine_room.flywheel.compat.backend.UniformStorage;
import dev.engine_room.flywheel.compat.backend.UniformStorageOps;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * LoD depth and main depth meet at the vanilla terrain radius R.
 * <ul>
 * <li>LoD depth -> main depth, each fragment pushed along its ray past R: occludes later draws, never real terrain
 * (DH also draws the overlap band).</li>
 * <li>Main depth -> DH's vanilla fade, cleared past R and wherever the front surface is not terrain: the fade
 * overwrites every drawn pixel past its band with LoD colour.</li>
 * </ul>
 */
final class LodDepthPass {
    private static final RenderPipeline DEPTH = RenderPipeline.builder()
            .withLocation(ResourceUtil.rl("pipeline/lod_depth"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(ResourceUtil.rl("core/lod_depth"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withImageSampler("LodDepthSampler")
                    .withUniform("LodDepth", UniformType.UNIFORM_BUFFER)
                    .build())
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 0.0f, 0.0f))
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_NONE))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    private static final RenderPipeline MASK = RenderPipeline.builder()
            .withLocation(ResourceUtil.rl("pipeline/lod_fade_mask"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(ResourceUtil.rl("core/lod_fade_mask"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withImageSampler("DepthSampler")
                    .withImageSampler("TerrainSampler")
                    .withImageSampler("BeforeTranslucentSampler")
                    .withImageSampler("AfterTranslucentSampler")
                    .withUniform("LodFadeMask", UniformType.UNIFORM_BUFFER)
                    .build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT,
                    ColorTargetState.WRITE_ALL))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    private static final UniformStorage<DepthUniform> DEPTH_UNIFORMS = UniformStorageOps.create(
            "flywheel:lod_depth", DepthUniform.SIZE, 1);
    // One per fade (opaque, transparent): each runs at most once a frame.
    private static final UniformStorage<MaskUniform> OPAQUE_MASK_UNIFORMS = UniformStorageOps.create(
            "flywheel:lod_fade_mask_opaque", MaskUniform.SIZE, 1);
    private static final UniformStorage<MaskUniform> TRANSPARENT_MASK_UNIFORMS = UniformStorageOps.create(
            "flywheel:lod_fade_mask_transparent", MaskUniform.SIZE, 1);
    private static @Nullable GpuTexture mask;
    private static @Nullable GpuTextureView maskView;
    // Main-depth snapshots: 0 after opaque terrain, 1 before translucent terrain, 2 after it.
    // 26.2: DH's opaque fade runs between 0 and 1; its transparent fade follows 2.
    // 26.3 native OIT: executeOit's HEAD transparent fade precedes 1; executeOutline's HEAD opaque fade follows 2.
    private static final GpuTexture[] terrain = new GpuTexture[3];
    private static final GpuTextureView[] terrainViews = new GpuTextureView[3];
    // Captures run only in frames following a fade.
    private static boolean fadeSeen, capturing, opaqueCaptured, translucentCaptured;

    static {
        LodCompat.depthInitialized();
    }

    private LodDepthPass() {
    }

    #if MC >= 26.3
    static void warm() {
        RenderPipelineCompiler.precompileNative(DEPTH, true);
        RenderPipelineCompiler.precompileNative(MASK, false);
    }
    #endif

    static void close() {
        if (mask != null) {
            DistantHorizonsCompat.releaseFadeMaskView(mask);
        }
        DEPTH_UNIFORMS.close();
        OPAQUE_MASK_UNIFORMS.close();
        TRANSPARENT_MASK_UNIFORMS.close();
        if (maskView != null) {
            maskView.close();
            maskView = null;
        }
        for (int i = 0; i < terrainViews.length; i++) {
            if (terrainViews[i] != null) {
                terrainViews[i].close();
                terrainViews[i] = null;
            }
        }
        if (mask != null) {
            mask.close();
            mask = null;
        }
        for (int i = 0; i < terrain.length; i++) {
            if (terrain[i] != null) {
                terrain[i].close();
                terrain[i] = null;
            }
        }
        fadeSeen = capturing = opaqueCaptured = translucentCaptured = false;
    }

    static void draw(GpuTextureView lodDepth, Matrix4fc lodInverseViewProjection, Matrix4fc viewProjection,
                     float lodFarDepth, boolean lodZeroToOne) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        DEPTH_UNIFORMS.endFrame();
        DepthUniform data = new DepthUniform(lodInverseViewProjection, viewProjection,
                lodFarDepth, lodZeroToOne, zeroToOne(), vanillaRadius());
        GpuBufferSlice uniform = DEPTH_UNIFORMS.writeUniform(data);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassOps.builder(() -> "flywheel:lod_depth")
                             .withColorAttachment(main.getColorTextureView())
                             .withDepthAttachment(main.getDepthTextureView())
                             .withRenderArea(new RenderPass.RenderArea(0, 0, main.width, main.height))
                             .build())) {
            pass.setRenderPipeline(DEPTH);
            pass.setUniform("LodDepthSampler", lodDepth,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("LodDepth", uniform);
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * @param stage 0: after opaque terrain; 1: before translucent terrain; 2: after it (again after a deferred draw).
     */
    static void captureTerrain(int stage) {
        if (stage == 0) {
            capturing = fadeSeen;
            fadeSeen = false;
            opaqueCaptured = false;
            translucentCaptured = false;
        }
        if (!capturing) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depth = main.getDepthTexture();
        GpuTexture target = terrain[stage];
        if (target == null || target.getWidth(0) != main.width || target.getHeight(0) != main.height
                || target.getFormat() != depth.getFormat()) {
            if (target != null) {
                terrainViews[stage].close();
                target.close();
            }
            target = terrain[stage] = RenderSystem.getDevice().createTexture("flywheel:lod_terrain_depth_" + stage,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, depth.getFormat(), main.width,
                    main.height, 1, 1);
            terrainViews[stage] = RenderSystem.getDevice().createTextureView(target);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, target, 0, 0, 0, 0, 0, main.width,
                main.height);
        opaqueCaptured |= stage == 0;
        translucentCaptured |= stage == 2;
    }

    /**
     * Rebuilds the fade's view of the main depth; {@link #fadeMask()} holds it until the next call.
     */
    static void prepareFadeMask(boolean transparent, Matrix4fc inverseViewProjection) {
        fadeSeen = true;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (mask == null || mask.getWidth(0) != main.width || mask.getHeight(0) != main.height) {
            if (mask != null) {
                maskView.close();
                mask.close();
            }
            mask = RenderSystem.getDevice().createTexture("flywheel:lod_fade_mask",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R32_FLOAT,
                    main.width, main.height, 1, 1);
            maskView = RenderSystem.getDevice().createTextureView(mask);
        }
        var uniforms = transparent ? TRANSPARENT_MASK_UNIFORMS : OPAQUE_MASK_UNIFORMS;
        uniforms.endFrame();
        MaskUniform data = new MaskUniform(inverseViewProjection, zeroToOne(), vanillaRadius(), opaqueCaptured);
        GpuBufferSlice uniform = uniforms.writeUniform(data);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassOps.builder(() -> "flywheel:lod_fade_mask")
                             .withColorAttachment(maskView)
                             .withRenderArea(new RenderPass.RenderArea(0, 0, main.width, main.height))
                             .build())) {
            pass.setRenderPipeline(MASK);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.setUniform("DepthSampler", main.getDepthTextureView(), sampler);
            GpuTextureView opaque = opaqueCaptured ? terrainViews[0] : main.getDepthTextureView();
            pass.setUniform("TerrainSampler", opaque, sampler);
            pass.setUniform("BeforeTranslucentSampler", translucentCaptured ? terrainViews[1] : opaque, sampler);
            pass.setUniform("AfterTranslucentSampler", translucentCaptured ? terrainViews[2] : opaque, sampler);
            pass.setUniform("LodFadeMask", uniform);
            pass.draw(3, 1, 0, 0);
        }
    }

    static GpuTexture fadeMask() {
        return mask;
    }

    private static boolean zeroToOne() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    }

    // >= horizontal extent of every vanilla and Sodium section
    private static float vanillaRadius() {
        return (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2 * Mth.SQRT_OF_TWO) * 16;
    }

    private record DepthUniform(Matrix4fc lodInverseViewProjection, Matrix4fc viewProjection, float lodFarDepth,
                                boolean lodZeroToOne, boolean zeroToOne, float vanillaRadius) implements UniformData {
        static final int SIZE = new Std140SizeCalculator().putMat4f()
                                                          .putMat4f()
                                                          .putFloat()
                                                          .putInt()
                                                          .putInt()
                                                          .putFloat()
                                                          .get();

        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                         .putMat4f(lodInverseViewProjection)
                         .putMat4f(viewProjection)
                         .putFloat(lodFarDepth)
                         .putInt(lodZeroToOne ? 1 : 0)
                         .putInt(zeroToOne ? 1 : 0)
                         .putFloat(vanillaRadius);
        }
    }

    private record MaskUniform(Matrix4fc inverseViewProjection, boolean zeroToOne, float vanillaRadius,
                               boolean terrainCaptured) implements UniformData {
        static final int SIZE = new Std140SizeCalculator().putMat4f()
                                                          .putInt()
                                                          .putFloat()
                                                          .putInt()
                                                          .get();

        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer)
                         .putMat4f(inverseViewProjection)
                         .putInt(zeroToOne ? 1 : 0)
                         .putFloat(vanillaRadius)
                         .putInt(terrainCaptured ? 1 : 0);
        }
    }
}
