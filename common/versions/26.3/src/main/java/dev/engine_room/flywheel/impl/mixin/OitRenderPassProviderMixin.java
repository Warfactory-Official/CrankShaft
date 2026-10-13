package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.engine_room.flywheel.impl.event.NativeOitRenderPassScope;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

@Mixin(OitRenderPassProvider.class)
abstract class OitRenderPassProviderMixin {
    @Inject(method = "createRenderPass", at = @At("HEAD"), cancellable = true)
    private static void flywheel$useEngineAttachments(OitStage stage, Supplier<String> label,
                                                     OitRenderPassProvider.Parameters parameters,
                                                     CallbackInfoReturnable<RenderPass> cir) {
        if (NativeOitRenderPassScope.active()) {
            cir.setReturnValue(NativeOitRenderPassScope.create(label, parameters));
        }
    }
}
