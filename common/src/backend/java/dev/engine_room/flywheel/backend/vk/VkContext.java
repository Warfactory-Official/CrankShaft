package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
#if MC >= 26.3
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
#endif
import dev.engine_room.flywheel.backend.NoiseTextures;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
#endif
import dev.engine_room.flywheel.backend.compile.VkCompilationTasks;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorHeap;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Objects;

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

    public static int mainDepthFormat() {
        GpuTexture depth = Objects.requireNonNull(Minecraft.getInstance().gameRenderer.mainRenderTarget()
                                                           .getDepthTexture());
        return VulkanConst.toVk(depth.getFormat());
    }

    // Metal rejects a pipeline/attachment stencil-format mismatch; NVIDIA tolerates it.
    public static int stencilFormat(int depthFormat) {
        return depthFormat == VK12.VK_FORMAT_D32_SFLOAT_S8_UINT || depthFormat == VK12.VK_FORMAT_D24_UNORM_S8_UINT
                ? depthFormat : VK12.VK_FORMAT_UNDEFINED;
    }

    public static void attachDepth(MemoryStack stack, VkRenderingInfo rendering, GpuTextureView depthView) {
        long view = imageView(depthView);
        rendering.pDepthAttachment(loadStore(stack, view));
        if (depthView.texture().getFormat().hasStencilAspect()) {
            rendering.pStencilAttachment(loadStore(stack, view));
        }
    }

    private static VkRenderingAttachmentInfo loadStore(MemoryStack stack, long view) {
        return VkRenderingAttachmentInfo.calloc(stack)
                                        .sType$Default()
                                        .imageView(view)
                                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                                        .loadOp(VK12.VK_ATTACHMENT_LOAD_OP_LOAD)
                                        .storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
    }

    public static VulkanCommandEncoder encoder() {
        return device().createCommandEncoder();
    }

    /** Render-thread access to the native pass while its host facade is open; the result cannot outlive it. */
    public static VulkanRenderPass renderPass(RenderPass pass) {
        #if MC >= 26.3
        return (VulkanRenderPass) ((FrontendRenderPass) pass).backend;
        #else
        return (VulkanRenderPass) pass.backend;
        #endif
    }

    public static void deferDestroy(Destroyable destroyable) {
        if (VkCompilationTasks.deferCleanup(destroyable)) return;
        encoder().queueForDestroy(destroyable);
    }

    /**
     * Render thread, from {@code VulkanDevice.close} ahead of its encoder (which flushes deferred destroys), allocator
     * and device: the engine's process-lifetime objects. Per-world engines are gone with their level.
     */
    public static void shutdown() {
        VK12.vkDeviceWaitIdle(vkDevice());
        #if MC >= 26.3
        RenderPipelineCompiler.reset();
        #endif
        VkPrograms.kill();
        VkShaderCompiler.shutdown();
        VkPipelineCaches.shutdown();
        VkBindlessTable.destroy();
        VkDescriptorHeap.destroy();
        VkGpuTimer.setEnabled(false);
        if (NoiseTextures.BLUE_NOISE != null) {
            NoiseTextures.BLUE_NOISE.close();
            NoiseTextures.BLUE_NOISE = null;
        }
    }

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
        #if MC >= 26.3
        return device == null ? null : ((FrontendGpuDevice) device).backend;
        #else
        return device == null ? null : device.backend;
        #endif
    }
}
