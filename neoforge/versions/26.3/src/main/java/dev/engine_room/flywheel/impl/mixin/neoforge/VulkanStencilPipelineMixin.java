package dev.engine_room.flywheel.impl.mixin.neoforge;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.engine_room.flywheel.impl.compat.NeoForgeVulkanStencilPipelines;
import dev.engine_room.flywheel.impl.extension.VulkanStencilPipelineAccess;
import net.neoforged.neoforge.client.ClientHooks;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.LongBuffer;

/** NeoForge's native stencil selector requires a compatible nonzero PSO even when stencil testing is disabled. */
@Mixin(VulkanRenderPipeline.class)
abstract class VulkanStencilPipelineMixin implements VulkanStencilPipelineAccess {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final @Mutable private long withDepthStencilPipeline;
    @Unique private long flywheel$d24Stencil;
    @Unique private long flywheel$d32Stencil;

    @WrapMethod(method = "compile")
    private static VulkanRenderPipeline flywheel$compileStencilFormats(VulkanDevice device,
            BackendRenderPipeline.CreateInfo info, Operation<VulkanRenderPipeline> original) {
        try (var compilation = NeoForgeVulkanStencilPipelines.begin(device)) {
            VulkanRenderPipeline pipeline = original.call(device, info);
            compilation.retain((VulkanStencilPipelineAccess) (Object) pipeline,
                    VulkanConst.toVk(ClientHooks.getStencilFormat()));
            return pipeline;
        }
    }

    @WrapOperation(method = "compile",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
            require = 3)
    private static int flywheel$createStencilFormats(VkDevice device, long cache,
            VkGraphicsPipelineCreateInfo.Buffer info,
            @Nullable VkAllocationCallbacks allocator, LongBuffer output, Operation<Integer> original) {
        var compilation = NeoForgeVulkanStencilPipelines.compiling();
        if (compilation.reuse(info, output)) return VK12.VK_SUCCESS;
        int result = original.call(device, cache, info, allocator, output);
        if (result == VK12.VK_SUCCESS) compilation.create(cache, info, allocator);
        return result;
    }

    @Override
    public void flywheel$stencilPipelines(long d24, long d32, int preferredFormat) {
        flywheel$d24Stencil = d24;
        flywheel$d32Stencil = d32;
        withDepthStencilPipeline = preferredFormat == VK12.VK_FORMAT_D24_UNORM_S8_UINT ? d24 : d32;
    }

    @Override
    public boolean flywheel$hasStencilPipelines() {
        return flywheel$d24Stencil != 0L || flywheel$d32Stencil != 0L;
    }

    @Override
    public long flywheel$stencilPipeline(int format) {
        long handle = switch (format) {
            case VK12.VK_FORMAT_D24_UNORM_S8_UINT -> flywheel$d24Stencil;
            case VK12.VK_FORMAT_D32_SFLOAT_S8_UINT -> flywheel$d32Stencil;
            default -> throw new UnsupportedOperationException("Unsupported native stencil attachment format "
                    + format);
        };
        if (handle == 0L) {
            throw new UnsupportedOperationException("Native stencil attachment format is unsupported by this device: "
                    + format);
        }
        return handle;
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$destroyStencilFormats(CallbackInfo ci) {
        if (flywheel$d24Stencil != 0L) VK12.vkDestroyPipeline(device.vkDevice(), flywheel$d24Stencil, null);
        if (flywheel$d32Stencil != 0L) VK12.vkDestroyPipeline(device.vkDevice(), flywheel$d32Stencil, null);
        flywheel$d24Stencil = flywheel$d32Stencil = 0L;
    }
}
