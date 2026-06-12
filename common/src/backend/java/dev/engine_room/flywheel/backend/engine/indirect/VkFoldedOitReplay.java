package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.OitMode;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface VkFoldedOitReplay {
    void replayFolded(VkCommandBuffer cmd, OitMode mode, OitFramebuffer framebuffer, GpuTextureView lightmapView,
                      GpuTextureView blueNoiseView, GpuSampler clampLinear, GpuSampler oitSampler,
                      GpuSampler noiseSampler);

    void replayMlab(VkCommandBuffer cmd, dev.engine_room.flywheel.backend.compile.OitInsertMode oitMode,
                    VkMlabBuffers mlab, GpuTextureView lightmapView, GpuSampler clampLinear);
}
