package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.compile.ShaderWarmupSplash;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Loading overlay stays installed during warm-up: reload deferral and input gating key on it. */
@Mixin(Gui.class)
abstract class GuiMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Overlay;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void flw$warmupSplash(Overlay overlay, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a,
                                  Operation<Void> original) {
        if (!ShaderWarmupSplash.extract(graphics)) original.call(overlay, graphics, mouseX, mouseY, a);
    }
}
