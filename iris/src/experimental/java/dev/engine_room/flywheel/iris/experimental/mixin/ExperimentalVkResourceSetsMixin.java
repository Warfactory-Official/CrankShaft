package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.compile.ExperimentalBindless;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.vulkan.VulkanResourceSets;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the shader's table declaration through Iris's asynchronous pipeline and library creation.
 */
@Mixin(VulkanResourceSets.class)
abstract class ExperimentalVkResourceSetsMixin {
    @Shadow
    private long layout;

    @Inject(method = "<init>(Lorg/lwjgl/vulkan/VkDevice;Lnet/irisshaders/iris/pipeline/transform/ShaderResourceLayout;I)V",
            at = @At("RETURN"))
    private void flywheel$registerTextureTable(VkDevice _, ShaderResourceLayout resources, int setIndex,
                                               CallbackInfo _) {
        ExperimentalBindless.registerLayout(layout, resources, setIndex);
    }

    @Inject(method = "close", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkDestroyDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
    private void flywheel$retireTextureTable(CallbackInfo _) {
        ExperimentalBindless.retireLayout(layout);
    }
}
