package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.engine_room.flywheel.iris.experimental.ExperimentalBlendEquations;
import net.irisshaders.iris.vulkan.VulkanGraphicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGraphicsPipeline.class)
abstract class ExperimentalVkBlendEquationMixin {
    @ModifyArg(method = "<init>(Lorg/lwjgl/vulkan/VkDevice;Lnet/irisshaders/iris/vulkan/VulkanSamplerHeap;Lnet/irisshaders/iris/vulkan/VulkanResourceSets;Ljava/util/Map;[IILjava/util/List;Ljava/util/List;Ljava/util/BitSet;Lnet/irisshaders/iris/vulkan/VulkanGraphicsPipeline$WorldState;ILnet/irisshaders/iris/vulkan/VulkanResourceSets;Lnet/irisshaders/iris/vulkan/VulkanGraphicsPipeline$ShaderLibrary;Z)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState;colorBlendOp(I)Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState;"), index = 0)
    private int flywheel$colorEquation(int original, @Local(name = "state") RenderPipeline carrier,
                                       @Local(name = "i") int attachment) {
        var blend = ExperimentalBlendEquations.function(carrier, attachment);
        return blend == null ? original : VulkanConst.toVk(blend.color().op());
    }

    @ModifyArg(method = "<init>(Lorg/lwjgl/vulkan/VkDevice;Lnet/irisshaders/iris/vulkan/VulkanSamplerHeap;Lnet/irisshaders/iris/vulkan/VulkanResourceSets;Ljava/util/Map;[IILjava/util/List;Ljava/util/List;Ljava/util/BitSet;Lnet/irisshaders/iris/vulkan/VulkanGraphicsPipeline$WorldState;ILnet/irisshaders/iris/vulkan/VulkanResourceSets;Lnet/irisshaders/iris/vulkan/VulkanGraphicsPipeline$ShaderLibrary;Z)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState;alphaBlendOp(I)Lorg/lwjgl/vulkan/VkPipelineColorBlendAttachmentState;"), index = 0)
    private int flywheel$alphaEquation(int original, @Local(name = "state") RenderPipeline carrier,
                                       @Local(name = "i") int attachment) {
        var blend = ExperimentalBlendEquations.function(carrier, attachment);
        return blend == null ? original : VulkanConst.toVk(blend.alpha().op());
    }
}
