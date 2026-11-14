package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;

import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.material.TerrainOverlay;
import dev.engine_room.flywheel.backend.compile.TerrainOverlayPipelines;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.lib.util.TerrainOverlays;
import dev.engine_room.flywheel.lib.util.TerrainReceivers;
import net.minecraft.client.Minecraft;

import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Frame resources for fragment shading fused into the terrain's ordinary draw.
 */
public final class TerrainOverlayDraw {
    private static long frame;
    private static long registrationRevision;
    private static List<TerrainOverlays.Registration> registrations = List.of();
    private static List<TerrainOverlay> overlays = List.of();
    private static List<Identifier> sources = List.of();
    private static @Nullable MappableRingBuffer projection;
    private static GpuBufferSlice frameUniforms;

    private TerrainOverlayDraw() {
    }

    public static void beginFrame(RenderContext context) {
        frame++;
        var frameRegistrations = List.copyOf(TerrainOverlays.registrations(context.level()));
        registrations = List.of();
        overlays = List.of();
        sources = List.of();
        registrationRevision = TerrainOverlays.revision();
        if (frameRegistrations.isEmpty()) {
            clearEmptyFrame();
            return;
        }
        TerrainReceiverBuffers.prepare();
        if (projection == null) projection = new MappableRingBuffer(() -> "Terrain overlay frame",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 64);
        else projection.rotate();
        var buffer = projection.currentBuffer();
        try (var mapped = buffer.map(false, true)) {
            new Matrix4f(context.viewProjection()).invert().get(0, mapped.data().order(ByteOrder.nativeOrder()));
        }
        frameUniforms = buffer.slice();
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        for (var registration : frameRegistrations)
            if (registration.isRegistered())
                registration.overlay().prepare(context, frame, target.width, target.height);
        registrations = frameRegistrations;
        updatePreparedOverlays();
    }

    /**
     * Advances the main level frame and retires unused resources without constructing a render context.
     */
    public static void beginEmptyFrame() {
        frame++;
        clearEmptyFrame();
    }

    private static void clearEmptyFrame() {
        close();
        if (!TerrainReceivers.active()) TerrainReceiverBuffers.close();
    }

    private static void updatePreparedOverlays() {
        overlays = registrations.stream().filter(TerrainOverlays.Registration::isRegistered)
                                .map(TerrainOverlays.Registration::overlay).toList();
        sources = overlays.stream().map(TerrainOverlay::fragmentSource).toList();
        registrationRevision = TerrainOverlays.revision();
    }

    private static void refreshRegistrations() {
        if (registrationRevision != TerrainOverlays.revision()) updatePreparedOverlays();
    }

    public static boolean isEmpty() {
        refreshRegistrations();
        return sources.isEmpty() || GuestTerrainGate.renderingShadow();
    }

    public static List<Identifier> sources() {
        return isEmpty() ? List.of() : sources;
    }

    public static List<TerrainOverlay> overlays() {
        return isEmpty() ? List.of() : overlays;
    }

    public static GpuBufferSlice frameUniforms() {
        return frameUniforms;
    }

    public static void bind(RenderPass pass) {
        if (isEmpty()) return;
        pass.setUniform("TerrainReceiverIds", TerrainReceiverBuffers.get(null));
        pass.setUniform("TerrainOverlayFrame", frameUniforms);
        pass.setUniform("TerrainOverlayLightmap", Minecraft.getInstance().gameRenderer.lightmap(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        for (int index = 0; index < overlays.size(); index++) {
            var overlay = overlays.get(index);
            pass.setUniform("TerrainOverlayData" + index, overlay.uniforms());
            pass.setUniform("TerrainOverlayTexture" + index, overlay.texture(), overlay.sampler());
            pass.setUniform("TerrainOverlayMetadata" + index, overlay.metadata(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
    }

    public static void bind(VkDescriptorWriter writer) {
        if (isEmpty()) return;
        writer.uniform(TerrainOverlayPipelines.FRAME_BINDING, frameUniforms);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        for (int index = 0; index < overlays.size(); index++) {
            var overlay = overlays.get(index);
            writer.uniform(TerrainOverlayPipelines.dataBinding(index), overlay.uniforms())
                  .sampler(TerrainOverlayPipelines.textureBinding(index),
                          VkContext.imageView(overlay.texture()), VkContext.sampler(overlay.sampler()))
                  .sampler(TerrainOverlayPipelines.metadataBinding(index),
                          VkContext.imageView(overlay.metadata()), VkContext.sampler(nearest));
        }
    }

    public static void bindGl(int program) {
        if (isEmpty()) return;
        bindGlBlock(program, "TerrainOverlayFrame", TerrainOverlayPipelines.FRAME_BINDING, frameUniforms);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "TerrainOverlayLightmap"), 2);
        for (int index = 0; index < overlays.size(); index++) {
            var overlay = overlays.get(index);
            bindGlBlock(program, "TerrainOverlayData" + index, TerrainOverlayPipelines.dataBinding(index),
                    overlay.uniforms());
            bindGlTexture(program, "TerrainOverlayTexture" + index, TerrainOverlayPipelines.textureBinding(index),
                    overlay.texture(), overlay.sampler());
            bindGlTexture(program, "TerrainOverlayMetadata" + index, TerrainOverlayPipelines.metadataBinding(index),
                    overlay.metadata(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private static void bindGlBlock(int program, String name, int binding, GpuBufferSlice data) {
        int block = GL31.glGetUniformBlockIndex(program, name);
        if (block == GL31.GL_INVALID_INDEX) return;
        GL31.glUniformBlockBinding(program, block, binding);
        GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, binding, ((GlBuffer) data.buffer()).handle(), data.offset(),
                data.length());
    }

    private static void bindGlTexture(int program, String name, int unit, GpuTextureView view, GpuSampler sampler) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), unit);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        var texture = (GlTexture) view.texture();
        // Raw engine units lie beyond Mojang's texture cache.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.glId());
        GlTextureLevelState.applyMipLevels(texture, view.baseMipLevel(), view.baseMipLevel() + view.mipLevels() - 1);
        GL33.glBindSampler(unit, ((GlSampler) sampler).getId());
    }

    public static void unbindGl() {
        for (int index = 0; index < overlays().size(); index++) {
            GL33.glBindSampler(TerrainOverlayPipelines.textureBinding(index), 0);
            GL33.glBindSampler(TerrainOverlayPipelines.metadataBinding(index), 0);
        }
    }

    public static void close() {
        registrations = List.of();
        overlays = List.of();
        sources = List.of();
        registrationRevision = TerrainOverlays.revision();
        closeProjection();
    }

    private static void closeProjection() {
        if (projection != null) {
            projection.close();
            projection = null;
        }
    }
}
