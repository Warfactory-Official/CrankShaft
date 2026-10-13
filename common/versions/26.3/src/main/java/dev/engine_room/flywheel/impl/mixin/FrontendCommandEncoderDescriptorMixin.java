package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
import dev.engine_room.flywheel.impl.event.RenderPassDescriptorAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FrontendCommandEncoder.class)
abstract class FrontendCommandEncoderDescriptorMixin {
    @Inject(
            method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At("HEAD"))
    private void flywheel$rejectNestedNativeOitPass(RenderPassDescriptor descriptor,
                                                   CallbackInfoReturnable<RenderPass> cir) {
        if (VkNativeOitPass.active()) throw new UnsupportedOperationException(
                "Arbitrary nested RenderPass creation is unsupported inside fused native OIT; custom clouds must use "
                + "supplied OIT passes");
    }

    @ModifyReturnValue(
            method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
            at = @At("RETURN"))
    private RenderPass flywheel$recordDescriptor(RenderPass pass, RenderPassDescriptor descriptor) {
        ((RenderPassDescriptorAccess) pass).flywheel$descriptor(descriptor);
        return pass;
    }
}
