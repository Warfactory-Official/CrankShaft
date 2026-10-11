package dev.engine_room.flywheel.impl.compat;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
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
                                                                                                  .withSampler("LodDepthSampler")
                                                                                                  .withUniform("LodDepth", UniformType.UNIFORM_BUFFER)
                                                                                                  .build())
                                                              .withDepthStencilState(new DepthStencilState(
                                                                      CompareOp.GREATER_THAN_OR_EQUAL, true))
                                                              .withColorTargetState(new ColorTargetState(Optional.empty(),
                                                                      GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                                                              .withCull(false)
                                                              .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                              .build();
    private static final RenderPipeline MASK = RenderPipeline.builder()
                                                             .withLocation(ResourceUtil.rl("pipeline/lod_fade_mask"))
                                                             .withVertexShader("core/screenquad")
                                                             .withFragmentShader(ResourceUtil.rl("core/lod_fade_mask"))
                                                             .withBindGroupLayout(BindGroupLayout.builder()
                                                                                                 .withSampler("DepthSampler")
                                                                                                 .withSampler("TerrainSampler")
                                                                                                 .withSampler("BeforeTranslucentSampler")
                                                                                                 .withSampler("AfterTranslucentSampler")
                                                                                                 .withUniform("LodFadeMask", UniformType.UNIFORM_BUFFER)
                                                                                                 .build())
                                                             .withColorTargetState(new ColorTargetState(Optional.empty(),
                                                                     GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                                                             .withCull(false)
                                                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                             .build();
    private static final DynamicUniformStorage<DepthUniform> DEPTH_UNIFORMS = new DynamicUniformStorage<>(
            "flywheel:lod_depth", DepthUniform.SIZE, 1);
    // One per fade (opaque, transparent): each runs at most once a frame.
    private static final DynamicUniformStorage<MaskUniform> OPAQUE_MASK_UNIFORMS = new DynamicUniformStorage<>(
            "flywheel:lod_fade_mask_opaque", MaskUniform.SIZE, 1);
    private static final DynamicUniformStorage<MaskUniform> TRANSPARENT_MASK_UNIFORMS = new DynamicUniformStorage<>(
            "flywheel:lod_fade_mask_transparent", MaskUniform.SIZE, 1);
    private static @Nullable GpuTexture mask;
    private static @Nullable GpuTextureView maskView;
    // Level frame's main depth: after opaque terrain, before and after the translucent terrain draw. The opaque fade
    // runs between the first two, the transparent fade after the third.
    private static final GpuTexture[] terrain = new GpuTexture[3];
    private static final GpuTextureView[] terrainViews = new GpuTextureView[3];
    // Captures run only in frames following a fade.
    private static boolean fadeSeen, capturing, opaqueCaptured, translucentCaptured;

    private LodDepthPass() {
    }

    static void draw(GpuTextureView lodDepth, Matrix4fc lodInverseViewProjection, Matrix4fc viewProjection,
                     float lodFarDepth, boolean lodZeroToOne) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        DEPTH_UNIFORMS.endFrame();
        GpuBufferSlice uniform = DEPTH_UNIFORMS.writeUniform(new DepthUniform(lodInverseViewProjection, viewProjection,
                lodFarDepth, lodZeroToOne, zeroToOne(), vanillaRadius()));
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.create(() -> "flywheel:lod_depth")
                                    .withColorAttachment(main.getColorTextureView())
                                    .withDepthAttachment(main.getDepthTextureView())
                                    .withRenderArea(new RenderPass.RenderArea(0, 0, main.width, main.height)))) {
            pass.setPipeline(DEPTH);
            pass.bindTexture("LodDepthSampler", lodDepth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
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
        DynamicUniformStorage<MaskUniform> uniforms = transparent ? TRANSPARENT_MASK_UNIFORMS : OPAQUE_MASK_UNIFORMS;
        uniforms.endFrame();
        GpuBufferSlice uniform = uniforms.writeUniform(new MaskUniform(inverseViewProjection, zeroToOne(),
                vanillaRadius(), opaqueCaptured));
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                RenderPassDescriptor.create(() -> "flywheel:lod_fade_mask")
                                    .withColorAttachment(maskView)
                                    .withRenderArea(new RenderPass.RenderArea(0, 0, main.width, main.height)))) {
            pass.setPipeline(MASK);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("DepthSampler", main.getDepthTextureView(), sampler);
            GpuTextureView opaque = opaqueCaptured ? terrainViews[0] : main.getDepthTextureView();
            pass.bindTexture("TerrainSampler", opaque, sampler);
            pass.bindTexture("BeforeTranslucentSampler", translucentCaptured ? terrainViews[1] : opaque, sampler);
            pass.bindTexture("AfterTranslucentSampler", translucentCaptured ? terrainViews[2] : opaque, sampler);
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
                                boolean lodZeroToOne, boolean zeroToOne, float vanillaRadius)
            implements DynamicUniformStorage.DynamicUniform {
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
                               boolean terrainCaptured) implements DynamicUniformStorage.DynamicUniform {
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
