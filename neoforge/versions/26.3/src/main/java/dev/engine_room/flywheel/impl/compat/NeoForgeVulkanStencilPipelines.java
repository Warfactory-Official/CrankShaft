package dev.engine_room.flywheel.impl.compat;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.engine_room.flywheel.impl.extension.VulkanStencilPipelineAccess;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

/** Creates exact stencil variants while the native compiler's complete pipeline state is still live. */
public final class NeoForgeVulkanStencilPipelines {
    private static final ThreadLocal<Compilation> COMPILING = new ThreadLocal<>();

    private NeoForgeVulkanStencilPipelines() {
    }

    public static Compilation begin(VulkanDevice device) {
        if (COMPILING.get() != null) throw new IllegalStateException("Native Vulkan pipeline compilation cannot nest");
        Compilation compilation = new Compilation(device.vkDevice());
        COMPILING.set(compilation);
        return compilation;
    }

    public static Compilation compiling() {
        return COMPILING.get();
    }

    public static final class Compilation implements AutoCloseable {
        private final VkDevice device;
        private long d24;
        private long d32;
        private boolean created;
        private boolean retained;

        private Compilation(VkDevice device) {
            this.device = device;
        }

        /** Reuses the eagerly created D32/S8 variant when stock compilation requests it again. */
        public boolean reuse(VkGraphicsPipelineCreateInfo.Buffer info, LongBuffer output) {
            if (!created) return false;
            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.create(info.get(0).pNext());
            if (rendering.depthAttachmentFormat() != VK12.VK_FORMAT_D32_SFLOAT_S8_UINT
                    || rendering.stencilAttachmentFormat() != VK12.VK_FORMAT_D32_SFLOAT_S8_UINT) return false;
            long handle = d32 != 0L ? d32 : d24;
            if (handle == 0L) return false;
            output.put(output.position(), handle);
            return true;
        }

        public void create(long cache, VkGraphicsPipelineCreateInfo.Buffer info,
                           @Nullable VkAllocationCallbacks allocator) {
            if (created) return;
            created = true;
            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.create(info.get(0).pNext());
            int depth = rendering.depthAttachmentFormat();
            int stencil = rendering.stencilAttachmentFormat();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkFormatProperties properties = VkFormatProperties.calloc(stack);
                LongBuffer output = stack.mallocLong(1);
                VK12.vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), VK12.VK_FORMAT_D24_UNORM_S8_UINT,
                        properties);
                if ((properties.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    d24 = create(cache, info, allocator, rendering, output, VK12.VK_FORMAT_D24_UNORM_S8_UINT);
                }
                VK12.vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), VK12.VK_FORMAT_D32_SFLOAT_S8_UINT,
                        properties);
                if ((properties.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    d32 = create(cache, info, allocator, rendering, output, VK12.VK_FORMAT_D32_SFLOAT_S8_UINT);
                }
            } finally {
                rendering.depthAttachmentFormat(depth).stencilAttachmentFormat(stencil);
            }
        }

        private long create(long cache, VkGraphicsPipelineCreateInfo.Buffer info,
                            @Nullable VkAllocationCallbacks allocator, VkPipelineRenderingCreateInfoKHR rendering,
                            LongBuffer output, int format) {
            rendering.depthAttachmentFormat(format).stencilAttachmentFormat(format);
            output.put(0, 0L);
            int result = VK12.vkCreateGraphicsPipelines(device, cache, info, allocator, output);
            if (result != VK12.VK_SUCCESS) {
                long partial = output.get(0);
                if (partial != 0L) VK12.vkDestroyPipeline(device, partial, allocator);
                throw new IllegalStateException("Vulkan error " + result + " creating native stencil format " + format);
            }
            return output.get(0);
        }

        public void retain(VulkanStencilPipelineAccess pipeline, int preferredFormat) {
            pipeline.flywheel$stencilPipelines(d24, d32, preferredFormat);
            retained = true;
        }

        @Override
        public void close() {
            COMPILING.remove();
            if (!retained) {
                if (d24 != 0L) VK12.vkDestroyPipeline(device, d24, null);
                if (d32 != 0L) VK12.vkDestroyPipeline(device, d32, null);
            }
        }
    }
}
