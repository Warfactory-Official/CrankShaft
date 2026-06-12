package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Scope Mojang's post-submit/copy memory barriers: each is a nuclear {@code ALL_COMMANDS} pipeline drain.
 * Passes owning both sides declare precise stage/access ({@code expect*}); others get the scoped publish.
 */
@Mixin(VulkanCommandEncoder.class)
abstract class VulkanCommandEncoderMixin {
    @Shadow
    private VkCommandBuffer commandBuffer() {
        throw new AssertionError();
    }

    @Redirect(method = "submitRenderPass", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
    private void flywheel$scopedRenderPassBarrier(VulkanCommandEncoder self, MemoryStack stack) {
        VkCommandBuffer cmd = this.commandBuffer();
        if (!FlwPassBarrier.emitIfPending(cmd, stack)) {
            FlwPassBarrier.emitDefault(cmd, stack);
        }
    }

    @Redirect(method = {
            "clearColorTexture",
            "clearDepthTexture",
            "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
            "writeToBuffer",
            "copyToBuffer",
            "writeToTexture",
            "copyBufferToTexture",
            "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
            "copyTextureToTexture"
    }, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"),
            require = 9)
    private void flywheel$scopedTransferBarrier(VulkanCommandEncoder self, MemoryStack stack) {
        FlwPassBarrier.emitTransferPublish(this.commandBuffer(), stack);
    }
}
