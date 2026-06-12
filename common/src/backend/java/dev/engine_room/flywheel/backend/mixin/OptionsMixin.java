package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.backend.engine.uniform.OptionsUniforms;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
abstract class OptionsMixin {
    @Inject(method = "load()V", at = @At("RETURN"), require = 1)
    private void flywheel$onLoad(CallbackInfo ci) {
        OptionsUniforms.update((Options) (Object) this);
    }

    @Inject(method = "save", at = @At("HEAD"), require = 1)
    private void flywheel$onSave(CallbackInfo ci) {
        OptionsUniforms.update((Options) (Object) this);
    }
}
