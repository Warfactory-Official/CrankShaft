package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;

import dev.engine_room.flywheel.backend.vk.VkContext;

/**
 * Retires Mojang {@link GpuBuffer}s that raw-VK work consumes OUTSIDE Mojang's usage tracking (raw
 * vertex/index binds, descriptor-writer binds, buffer-device-address reads). Mojang defers a closed buffer's
 * destruction only against submits where IT saw the buffer bound, so an immediate close destroys the memory
 * under still-in-flight raw readers -- a device loss surfacing moments after whatever rebuilt the buffer
 * (e.g. a new visual type growing the mesh pool). On a Vulkan host the close rides Minecraft's per-submit
 * destruction queue instead; GL closes directly (GL object lifetime covers in-flight use).
 */
public final class BufferRetirement {
    private BufferRetirement() {
    }

    public static void retire(GpuBuffer buffer) {
        if (VkContext.isVulkanHost()) {
            VkContext.deferDestroy(buffer::close);
        } else {
            buffer.close();
        }
    }
}
