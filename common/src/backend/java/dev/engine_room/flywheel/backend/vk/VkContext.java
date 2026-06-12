package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.*;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDebugUtilsLabelEXT;
import org.lwjgl.vulkan.VkDevice;

public final class VkContext {
    private static boolean labelsUnavailable;

    private VkContext() {
    }

    public static boolean isVulkanHost() {
        return activeBackend() instanceof VulkanDevice;
    }

    public static VulkanDevice device() {
        if (!(activeBackend() instanceof VulkanDevice vulkanDevice)) {
            throw new IllegalStateException("Flywheel's Vulkan backend requires an active Vulkan GpuDevice");
        }
        return vulkanDevice;
    }

    public static VkDevice vkDevice() {
        return device().vkDevice();
    }

    public static long vma() {
        return device().vma();
    }

    public static long imageView(GpuTextureView view) {
        return ((VulkanGpuTextureView) view).vkImageView();
    }

    public static long sampler(GpuSampler sampler) {
        return ((VulkanGpuSampler) sampler).vkSampler();
    }

    public static long buffer(GpuBuffer buffer) {
        return ((VulkanGpuBuffer) buffer).vkBuffer();
    }

    public static VulkanCommandEncoder encoder() {
        return device().createCommandEncoder();
    }

    public static void deferDestroy(Destroyable destroyable) {
        encoder().queueForDestroy(destroyable);
    }

    /**
     * record in true chronological order into the encoder's single frame submit (vanilla ends and submits the buffer
     * at its next seam). A standalone transient buffer spliced via {@code execute()} is deliberately NOT used -- the
     * splice reorders around vanilla's pre-registered open buffer.
     */
    public static VkCommandBuffer beginCommands() {
        VulkanCommandEncoder encoder = encoder();
        if (encoder.currentRenderPass != null) {
            throw new IllegalStateException("Flywheel raw commands must not record inside an open vanilla RenderPass");
        }
        VkCommandBuffer cmd = encoder.commandBuffer();
        checkpoint(cmd, CheckpointExtension.CheckpointType.BEGIN_RENDER_PASS, "flw/raw");
        return cmd;
    }

    public static void submitCommands(VkCommandBuffer cmd) {
        checkpoint(cmd, CheckpointExtension.CheckpointType.END_RENDER_PASS, "flw/raw");
    }

    private static void checkpoint(VkCommandBuffer cmd, CheckpointExtension.CheckpointType type, String name) {
        encoder().checkpointStorage.recordCheckpoint(cmd, type, () -> name);
    }

    public static void pushLabel(VkCommandBuffer cmd, String name) {
        checkpoint(cmd, CheckpointExtension.CheckpointType.BEGIN_RENDER_PASS, name);
        VkGpuTimer.push(cmd, name);
        if (labelsUnavailable) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            EXTDebugUtils.vkCmdBeginDebugUtilsLabelEXT(cmd,
                    VkDebugUtilsLabelEXT.calloc(stack).sType$Default().pLabelName(stack.UTF8(name)));
        } catch (Throwable t) {
            labelsUnavailable = true;
        }
    }

    public static void popLabel(VkCommandBuffer cmd) {
        VkGpuTimer.pop(cmd);
        if (labelsUnavailable) {
            return;
        }
        try {
            EXTDebugUtils.vkCmdEndDebugUtilsLabelEXT(cmd);
        } catch (Throwable t) {
            labelsUnavailable = true;
        }
    }

    private static @Nullable GpuDeviceBackend activeBackend() {
        GpuDevice device = RenderSystem.tryGetDevice();
        return device == null ? null : device.backend;
    }
}
