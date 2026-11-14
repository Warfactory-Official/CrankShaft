package dev.engine_room.flywheel.impl.mixin.sodium;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.impl.config.SodiumOptionVisibility;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.gui.widgets.PageListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PageListWidget.class)
abstract class SodiumPageListMixin {
    @WrapOperation(method = "rebuild", at = @At(value = "INVOKE", target =
            "Lnet/caffeinemc/mods/sodium/client/config/structure/ModOptions;pages()Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<Page> flywheel$visiblePages(ModOptions mod, Operation<ImmutableList<Page>> original) {
        return SodiumOptionVisibility.pages(original.call(mod));
    }
}
