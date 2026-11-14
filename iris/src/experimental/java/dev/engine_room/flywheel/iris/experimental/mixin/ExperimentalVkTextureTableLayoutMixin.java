package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import dev.engine_room.flywheel.iris.compile.ExperimentalBindless;
import dev.engine_room.flywheel.iris.experimental.TextureTableLayout;
import net.irisshaders.iris.vulkan.VulkanSamplerHeap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Properties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.LongBuffer;

@Mixin(VulkanSamplerHeap.class)
abstract class ExperimentalVkTextureTableLayoutMixin implements TextureTableLayout {
    @Shadow
    @Final
    private VkDevice device;
    @Shadow
    private long emptyLayout;
    @Unique
    private boolean flywheel$textureTableSupported;
    @Unique
    private ExperimentalBindless.Limits flywheel$textureTableLimits;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$checkTextureTable(CallbackInfo _) {
        if (!VkCaps.BINDLESS_TEXTURES_NEGOTIATED || VkCaps.MAX_BOUND_DESCRIPTOR_SETS <= ExperimentalBindless.SET)
            return;
        try (var stack = MemoryStack.stackPush()) {
            var indexing = VkPhysicalDeviceVulkan12Properties.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(),
                    VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(indexing));
            // Iris heap update-after-bind => these limits count every set, table included.
            flywheel$textureTableLimits = new ExperimentalBindless.Limits(
                    Integer.toUnsignedLong(indexing.maxPerStageDescriptorUpdateAfterBindSamplers()),
                    Integer.toUnsignedLong(indexing.maxDescriptorSetUpdateAfterBindSamplers()),
                    Integer.toUnsignedLong(indexing.maxPerStageDescriptorUpdateAfterBindSampledImages()),
                    Integer.toUnsignedLong(indexing.maxDescriptorSetUpdateAfterBindSampledImages()),
                    Integer.toUnsignedLong(indexing.maxPerStageUpdateAfterBindResources()));
            flywheel$textureTableSupported = ExperimentalBindless.fits(flywheel$textureTableLimits);
        }
    }

    @Override
    public boolean flywheel$supportsTextureTable() {
        return flywheel$textureTableSupported;
    }

    @Override
    public ExperimentalBindless.Limits flywheel$textureTableLimits() {
        return flywheel$textureTableLimits;
    }

    /**
     * Absent frame/draw sets => Iris's empty layout: table set index fixed in the shader.
     */
    @Inject(method = "pipelineSetLayouts(Lorg/lwjgl/system/MemoryStack;JJJ)Ljava/nio/LongBuffer;",
            at = @At("RETURN"), cancellable = true)
    private void flywheel$textureTable(MemoryStack stack, long uniforms, long _, long _,
                                       CallbackInfoReturnable<LongBuffer> cir) {
        if (!flywheel$textureTableSupported || !ExperimentalBindless.usesTable(uniforms)) return;
        long table = VkBindlessTable.existingSetLayout();
        if (table == 0L) throw new IllegalStateException("Guest texture table layout released before its pipeline");
        LongBuffer sets = cir.getReturnValue();
        LongBuffer layouts = stack.mallocLong(ExperimentalBindless.SET + 1);
        for (int set = 0; set < ExperimentalBindless.SET; set++)
            layouts.put(set, set < sets.remaining() ? sets.get(sets.position() + set) : emptyLayout);
        cir.setReturnValue(layouts.put(ExperimentalBindless.SET, table));
    }
}
