package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.impl.config.SodiumDropdownMenu;
import dev.engine_room.flywheel.impl.config.SodiumDropdownView;
import dev.engine_room.flywheel.impl.config.SodiumOptionVisibility;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.caffeinemc.mods.sodium.client.gui.prompt.ScreenPrompt;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;
import net.caffeinemc.mods.sodium.client.gui.widgets.SearchWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(VideoSettingsScreen.class)
abstract class SodiumVideoSettingsMixin implements SodiumDropdownView {
    @Shadow
    private @Nullable ScreenPrompt prompt;
    @Shadow
    private OptionListWidget optionList;
    @Shadow
    private SearchWidget searchWidget;

    @Shadow
    private void rebuild() {
        throw new AssertionError();
    }

    @Unique
    private SodiumOptionVisibility.State flywheel$visibility;
    @Unique
    private @Nullable SodiumDropdownMenu flywheel$overlay;

    @Override
    public SodiumDropdownMenu flywheel$dropdownMenu() {
        if (flywheel$overlay == null) flywheel$overlay = new SodiumDropdownMenu((Screen) (Object) this);
        return flywheel$overlay;
    }

    @Inject(method = "rebuild", at = @At("HEAD"))
    private void flywheel$closeTransientUi(CallbackInfo _) {
        flywheel$visibility = SodiumOptionVisibility.capture();
        SodiumOptionVisibility.install(flywheel$visibility);
        if (flywheel$overlay != null) flywheel$overlay.close();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void flywheel$popupHover(GuiGraphicsExtractor _, int mouseX, int mouseY, float _,
                                     CallbackInfo _) {
        var state = SodiumOptionVisibility.capture();
        if (!state.equals(flywheel$visibility)) {
            int scroll = optionList.getScrollAmount();
            var oldSearch = flywheel$searchBox();
            var query = oldSearch.getValue();
            boolean focused = oldSearch.isFocused();
            rebuild();
            var search = flywheel$searchBox();
            search.setValue(query);
            search.setFocused(focused);
            optionList.flywheel$restoreScroll(scroll);
        }
        if (flywheel$overlay == null) return;
        if (prompt != null) flywheel$overlay.close();
        else flywheel$overlay.updateHover(mouseX, mouseY);
    }

    @Unique
    private EditBox flywheel$searchBox() {
        return searchWidget.children().stream().filter(EditBox.class::isInstance)
                           .map(EditBox.class::cast).findFirst().orElseThrow();
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void flywheel$popupDraw(GuiGraphicsExtractor graphics, int _, int _, float _,
                                    CallbackInfo _) {
        if (flywheel$overlay != null && prompt == null) flywheel$overlay.draw(graphics);
    }

    // Dropdown menus own input while open; ordinary tooltips keep Sodium's native hover rules.
    @ModifyArgs(method = "updateControls", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gui/widgets/ScrollableTooltip;onControlHover(Lnet/caffeinemc/mods/sodium/client/gui/options/control/ControlElement;II)V"))
    private void flywheel$dropdownHover(Args args) {
        if (flywheel$overlay != null && flywheel$overlay.isOpen()) {
            args.set(0, flywheel$overlay.tooltipTarget(args.get(0)));
        }
    }

    @ModifyArgs(method = "extractRenderState", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void flywheel$obscureBackgroundControls(Args args) {
        if (flywheel$overlay != null && flywheel$overlay.isOpen()) {
            args.set(1, -1);
            args.set(2, -1);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void flywheel$popupKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (prompt == null && flywheel$overlay != null && flywheel$overlay.keyPressed(event)) cir.setReturnValue(true);
    }

    @Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true)
    private void flywheel$popupKeyRelease(KeyEvent _, CallbackInfoReturnable<Boolean> cir) {
        if (prompt == null && flywheel$overlay != null && flywheel$overlay.isOpen()) cir.setReturnValue(true);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void flywheel$popupClick(MouseButtonEvent event, boolean _, CallbackInfoReturnable<Boolean> cir) {
        if (prompt == null && flywheel$overlay != null && flywheel$overlay.mouseClicked(event))
            cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void flywheel$popupScroll(double x, double y, double _, double vertical,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (prompt == null && flywheel$overlay != null && flywheel$overlay.mouseScrolled(x, y, vertical))
            cir.setReturnValue(true);
    }
}
