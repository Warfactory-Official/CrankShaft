package dev.engine_room.flywheel.impl.mixin.neoforge;

#if MC >= 26.3
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
#else
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
#endif
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanRenderPipeline.class)
abstract class VulkanPipelineDestroyMixin {
    @Shadow
    @Final
    private VulkanDevice device;
    @Shadow
    @Final
    private long withDepthPipeline;
    @Shadow
    @Final
    private long withoutDepthPipeline;
    @Shadow
    @Final
    private long withDepthStencilPipeline;
    #if MC >= 26.3
    @Shadow @Final private long withStencilPipeline;
    #endif

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$destroyStencilVariants(CallbackInfo _) {
        // Engine pipelines can share the exact-depth handle with the native stencil selector.
        #if MC < 26.3
        if (withDepthStencilPipeline != withDepthPipeline && withDepthStencilPipeline != withoutDepthPipeline)
            VK10.vkDestroyPipeline(device.vkDevice(), withDepthStencilPipeline, null);
        #else
        // VulkanStencilPipelineMixin owns the 26.3 depth/stencil format variants.
        if(withStencilPipeline!=withDepthPipeline&&withStencilPipeline!=withoutDepthPipeline&&withStencilPipeline!=withDepthStencilPipeline)
            VK12.vkDestroyPipeline(device.vkDevice(),withStencilPipeline,null);
        #endif
    }
}
