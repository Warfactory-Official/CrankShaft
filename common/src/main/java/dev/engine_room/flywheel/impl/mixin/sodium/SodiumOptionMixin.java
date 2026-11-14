package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.impl.config.SodiumOptionTables;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Option.class)
abstract class SodiumOptionMixin {
    @Shadow
    @Final
    Identifier id;

    @Shadow
    Control control;

    @Inject(method = "getControl", at = @At("HEAD"))
    private void flywheel$tableControl(CallbackInfoReturnable<Control> _) {
        if (control == null) control = SodiumOptionTables.createControl(id, (Option) (Object) this);
    }

}
