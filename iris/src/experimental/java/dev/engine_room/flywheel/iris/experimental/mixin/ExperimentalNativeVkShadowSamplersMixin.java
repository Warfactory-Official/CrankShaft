package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.vulkan.VulkanSamplerHeap;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * A native-depth pack's hardware shadow comparisons follow its reversed shadow depth. The heap outlives packs and keys
 * its samplers by description alone, so reversed comparison samplers live in their own map.
 */
@Mixin(VulkanSamplerHeap.class)
abstract class ExperimentalNativeVkShadowSamplersMixin {
    @Shadow
    @Final
    private VkDevice device;

    @Unique
    private final Map<GlSampler.Description, Long> flywheel$reversedSamplers = new HashMap<>();

    @ModifyExpressionValue(method = "sampler(Lnet/irisshaders/iris/gl/sampler/GlSampler$Description;)J",
            at = @At(value = "FIELD", target = "Lnet/irisshaders/iris/vulkan/VulkanSamplerHeap;samplers:Ljava/util/Map;"))
    private Map<GlSampler.Description, Long> flywheel$samplerMap(Map<GlSampler.Description, Long> samplers,
                                                                 @Local(argsOnly = true) GlSampler.Description description) {
        return description.hardwareShadow() && NativeDepth.active() ? flywheel$reversedSamplers : samplers;
    }

    // The compare op is inert without compare enabled.
    @ModifyArg(method = "createSampler", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;compareOp(I)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"))
    private int flywheel$reversedCompare(int compare) {
        return NativeDepth.active() ? VK10.VK_COMPARE_OP_GREATER_OR_EQUAL : compare;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void flywheel$closeReversedSamplers(CallbackInfo _) {
        flywheel$reversedSamplers.values().forEach(sampler -> VK10.vkDestroySampler(device, sampler, null));
        flywheel$reversedSamplers.clear();
    }
}
