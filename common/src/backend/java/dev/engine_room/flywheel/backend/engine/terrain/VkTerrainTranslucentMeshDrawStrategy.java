package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface VkTerrainTranslucentMeshDrawStrategy {
    void prepareCull(VkTerrainDrawManager manager, GpuTexture oitDepth, int width, int height);

    void draw(VkTerrainDrawManager manager, OitMode mode, VkCommandBuffer cmd, OitFramebuffer framebuffer,
              boolean fading,
              GpuTextureView lightmapView, GpuTextureView blueNoiseView, GpuSampler clampLinear, GpuSampler oitSampler,
              GpuSampler noiseSampler, boolean localRead);

    void drawMlab(VkTerrainDrawManager manager, dev.engine_room.flywheel.backend.compile.OitInsertMode oitMode,
                  VkCommandBuffer cmd, boolean fading, VkMlabBuffers mlab, GpuTextureView lightmapView,
                  GpuSampler clampLinear);
}
