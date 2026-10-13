package dev.engine_room.flywheel.iris.experimental.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.engine_room.flywheel.iris.experimental.IndirectCommands;
import net.irisshaders.iris.vulkan.VulkanWorldCommands;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VulkanWorldCommands.class)
abstract class ExperimentalVkIndirectCountMixin implements IndirectCommands {
    @Shadow
    @Final
    private VkCommandBuffer command;

    @Override
    public void flywheel$drawIndexedIndirect(GpuBufferSlice commands, int count, int stride) {
        VK10.vkCmdDrawIndexedIndirect(command, ((VulkanGpuBuffer) commands.buffer()).vkBuffer(), commands.offset(),
                count, stride);
    }

    @Override
    public void flywheel$drawIndexedIndirectCount(GpuBufferSlice commands, GpuBufferSlice count, int maximum) {
        VK12.vkCmdDrawIndexedIndirectCount(command, ((VulkanGpuBuffer) commands.buffer()).vkBuffer(), commands.offset(),
                ((VulkanGpuBuffer) count.buffer()).vkBuffer(), count.offset(), maximum, 20);
    }
}
