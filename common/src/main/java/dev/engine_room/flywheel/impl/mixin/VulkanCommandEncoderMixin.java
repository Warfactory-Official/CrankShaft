package dev.engine_room.flywheel.impl.mixin;

import com.google.common.math.IntMath;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Scope Mojang's post-submit/copy memory barriers: each is a nuclear {@code ALL_COMMANDS} pipeline drain.
 * Passes owning both sides declare precise stage/access ({@code expect*}); others get the scoped publish.
 */
// Compat with Iris (Vulkan): priority < Iris's => its submitRenderPass barrier wrap stays outer. It owns world passes and
// may hold them open; our barrier there lands inside the render pass.
@Mixin(value = VulkanCommandEncoder.class, priority = 999)
abstract class VulkanCommandEncoderMixin {
    @ModifyConstant(method =
            #if MC >= 26.3
            "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            #else
            "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            #endif
            constant = @Constant(longValue = 1L), require = 1)
    private long flywheel$texelUploadAlignment(long _, @Local(argsOnly = true) GpuTexture destination) {
        int blockSize = destination.getFormat().blockSize();
        return 4L / IntMath.gcd(4, blockSize) * blockSize;
    }

    @Shadow
    private VkCommandBuffer commandBuffer() {
        throw new AssertionError();
    }

    @WrapOperation(method = "submitRenderPass",
            at = @At(value = "INVOKE",
                    target =
                    #if MC >= 26.3
                    "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
                    #else
                    "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
                    #endif
    private void flywheel$scopedRenderPassBarrier(VulkanCommandEncoder _, MemoryStack stack,
                                                  Operation<Void> _) {
        VkCommandBuffer cmd = commandBuffer();
        if (FlwPassBarrier.emitIfPending(cmd, stack)) {
            return;
        }
        FlwPassBarrier.emitDefault(cmd, stack);
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
    private void flywheel$scopedTransferBarrier(VulkanCommandEncoder _, MemoryStack stack) {
        FlwPassBarrier.emitTransferPublish(commandBuffer(), stack);
    }
}
