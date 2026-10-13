package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.engine_room.flywheel.backend.compile.VkNativeOitPipelines;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Final;
#endif
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorHeap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every {@code pushDescriptors} call invalidates the engine's descriptor-buffer binding on the shared command buffer.
 */
@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassMixin {
    @Shadow
    private boolean anyDescriptorDirty;

    #if MC >= 26.3
    @Shadow @Final public VkCommandBuffer commandBuffer;
    @Shadow protected VulkanRenderPipeline pipeline;

    @WrapWithCondition(method = "pushDescriptors",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRPushDescriptor;vkCmdPushDescriptorSetKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;IJILorg/lwjgl/vulkan/VkWriteDescriptorSet$Buffer;)V"))
    private boolean flywheel$pushNonemptyDescriptors(VkCommandBuffer commandBuffer, int bindPoint,
                                                     long layout, int set, VkWriteDescriptorSet.Buffer writes) {
        // 26.3: a native OIT utility can expose only engine input descriptors, which the RETURN hook supplies.
        return writes.hasRemaining();
    }

    @Inject(method = "pushDescriptors", at = @At("RETURN"))
    private void flywheel$pushNativeOitDescriptors(CallbackInfo ci) {
        VkNativeOitPipelines.pushDescriptors(commandBuffer, pipeline);
    }
    #endif

    @Inject(method = "pushDescriptors", at = @At("HEAD"))
    private void flywheel$notePushDescriptors(CallbackInfo ci) {
        if (anyDescriptorDirty) {
            VkDescriptorHeap.notifyForeignBind();
        }
    }
}
