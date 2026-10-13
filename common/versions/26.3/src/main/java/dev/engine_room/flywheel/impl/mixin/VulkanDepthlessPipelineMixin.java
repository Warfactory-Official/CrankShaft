package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native close queues destruction once; depthless-only owners need the same resource cleanup as depth owners. */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanDepthlessPipelineMixin {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private long withDepthPipeline;
    @Shadow @Final @Mutable private long withoutDepthPipeline;
    @Shadow @Final @Mutable private long pipelineLayout;
    @Shadow @Final @Mutable private long descriptorSetLayout;
    @Shadow @Final private LongList shaderModules;

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$destroyDepthlessOwner(CallbackInfo ci) {
        // Native compilation always creates a depth variant. Engine depthless owners deliberately do not.
        if (withDepthPipeline != 0 || withoutDepthPipeline == 0) return;
        VK12.vkDestroyPipeline(device.vkDevice(), withoutDepthPipeline, null);
        VK12.vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(device.vkDevice(), descriptorSetLayout, null);
        for (int i = 0; i < shaderModules.size(); i++) {
            VK12.vkDestroyShaderModule(device.vkDevice(), shaderModules.getLong(i), null);
        }
        withoutDepthPipeline = pipelineLayout = descriptorSetLayout = 0;
        shaderModules.clear();
    }
}
