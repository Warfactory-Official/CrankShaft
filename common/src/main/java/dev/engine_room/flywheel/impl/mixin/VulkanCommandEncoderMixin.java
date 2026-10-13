package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import dev.engine_room.flywheel.impl.compat.CompatMod;
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

    // Compat with Vitrail: it redirects this barrier too (its pack passes need compute-stage visibility, which the
    // scoped publish lacks) => wrap, and leave passes we do not own to it.
    @WrapOperation(method = "submitRenderPass",
            at = @At(value = "INVOKE",
                    target =
                    #if MC >= 26.3
                    "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
                    #else
                    "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
                    #endif
    private void flywheel$scopedRenderPassBarrier(VulkanCommandEncoder self, MemoryStack stack,
                                                  Operation<Void> original) {
        VkCommandBuffer cmd = this.commandBuffer();
        if (FlwPassBarrier.emitIfPending(cmd, stack)) {
            return;
        }
        if (CompatMod.VITRAIL.isLoaded) {
            original.call(self, stack);
        } else {
            FlwPassBarrier.emitDefault(cmd, stack);
        }
    }

    @Redirect(
            method = {
                    "clearColorTexture",
                    "clearDepthTexture",
                    #if MC >= 26.3
                    "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
                    #else
                    "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
                    #endif
                    "writeToBuffer",
                    "copyToBuffer",
                    "writeToTexture",
                    "copyBufferToTexture",
                    #if MC >= 26.3
                    "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
                    #else
                    "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
                    #endif
                    "copyTextureToTexture"
            },
            at = @At(value = "INVOKE",
                    target =
                    #if MC >= 26.3
                    "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"),
                    #else
                    "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"),
                    #endif
            require = 9)
    private void flywheel$scopedTransferBarrier(VulkanCommandEncoder self, MemoryStack stack) {
        FlwPassBarrier.emitTransferPublish(this.commandBuffer(), stack);
    }
}
