package dev.engine_room.flywheel.iris.experimental.mixin;

import net.irisshaders.iris.vulkan.VulkanGraphicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanGraphicsPipeline.class)
public interface ExperimentalVkPipelineLayoutAccessor {
    @Accessor("pipelineLayout")
    long flywheel$pipelineLayout();
}
