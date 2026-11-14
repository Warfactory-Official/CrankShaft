package dev.engine_room.flywheel.iris.experimental.mixin;

import net.irisshaders.iris.vulkan.VulkanGraphicsPipeline;
import net.irisshaders.iris.vulkan.VulkanGraphicsShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanGraphicsShader.Pipeline.class)
public interface ExperimentalVkShaderPipelineAccessor {
    @Accessor("pipeline")
    VulkanGraphicsPipeline flywheel$pipeline();

    @Accessor("vanillaPipeline")
    VulkanGraphicsPipeline flywheel$vanillaPipeline();
}
