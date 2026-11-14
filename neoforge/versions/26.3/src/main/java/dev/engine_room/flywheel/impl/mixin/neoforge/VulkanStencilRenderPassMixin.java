package dev.engine_room.flywheel.impl.mixin.neoforge;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.engine_room.flywheel.impl.extension.VulkanStencilPassAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Uses the supplied attachment's actual precision, independently of the configured main-target format.
 */
@Mixin(VulkanRenderPass.class)
abstract class VulkanStencilRenderPassMixin implements VulkanStencilPassAccess {
    @Shadow
    protected VulkanRenderPipeline pipeline;
    @Shadow
    @Final
    private boolean hasStencil;
    @Shadow
    @Final
    private boolean hasDepth;
    @Unique
    private int flywheel$depthFormat;

    @Override
    public void flywheel$depthFormat(int format) {
        flywheel$depthFormat = format;
    }

    @ModifyArg(method = "setPipeline",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCmdBindPipeline(Lorg/lwjgl/vulkan/VkCommandBuffer;IJ)V"),
            index = 2,
            require = 2)
    private long flywheel$bindActualStencilFormat(long handle) {
        if (!hasStencil || !hasDepth) return handle;
        return pipeline.flywheel$hasStencilPipelines() ? pipeline.flywheel$stencilPipeline(flywheel$depthFormat)
                : handle;
    }
}
