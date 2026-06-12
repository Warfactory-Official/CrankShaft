package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Per-frame handles threaded through the OIT producer passes; built once per {@code renderOit} by both draw managers.
 */
public record OitFrame(CommandEncoder encoder, @Nullable GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer,
                       GpuBufferSlice dynamicTransforms, GpuTextureView overlayView, GpuTextureView lightmapView,
                       GpuSampler loSampler, GpuSampler oitSampler, GpuSampler noiseSampler,
                       GpuTextureView blueNoiseView, TextureManager textureManager) {

    /**
     * Resizes {@code framebuffer} to the main target (allocating the wavelet chain targets), then
     * {@link #capture captures} the frame-constant handles.
     */
    public static OitFrame prepare(OitFramebuffer framebuffer, Matrix4fc renderModelView,
                                   @Nullable GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer,
                                   GpuTextureView blueNoiseView) {
        framebuffer.prepare();
        return capture(renderModelView, vertexBuffer, indexBuffer, blueNoiseView);
    }

    /**
     * Opens an encoder and captures the frame-constant samplers/views shared by every OIT producer pass this
     * frame -- no framebuffer sizing (the insert chain sizes its layer targets separately). A null
     * {@code blueNoiseView} means the caller's producers read no blue noise; the lightmap view fills the
     * record's non-null slot as a never-sampled placeholder.
     */
    public static OitFrame capture(Matrix4fc renderModelView, @Nullable GpuBuffer vertexBuffer,
                                   @Nullable GpuBuffer indexBuffer, @Nullable GpuTextureView blueNoiseView) {
        CommandEncoder encoder = RenderSystem.getDevice()
                                             .createCommandEncoder();
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(renderModelView));

        GpuSampler loSampler = RenderSystem.getSamplerCache()
                                           .getClampToEdge(FilterMode.LINEAR);
        GpuSampler oitSampler = RenderSystem.getSamplerCache()
                                            .getClampToEdge(FilterMode.NEAREST);
        GpuSampler noiseSampler = RenderSystem.getSamplerCache()
                                              .getRepeat(FilterMode.LINEAR);

        Minecraft mc = Minecraft.getInstance();
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        return new OitFrame(encoder, vertexBuffer, indexBuffer, dynamicTransforms, overlayView, lightmapView,
                loSampler, oitSampler, noiseSampler, blueNoiseView != null ? blueNoiseView : lightmapView,
                mc.getTextureManager());
    }
}
