package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.impl.config.SodiumDropdownView;
import dev.engine_room.flywheel.impl.config.SodiumOptionTables;
import dev.engine_room.flywheel.impl.config.SodiumOptionTooltips;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.widgets.ScrollableTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ScrollableTooltip.class)
abstract class SodiumTooltipPreviewMixin {
    @Shadow
    private @Nullable ControlElement hoveredElement;
    @Shadow
    @Final
    private ScrollableTooltip.TooltipParent parent;

    @Shadow
    public abstract void onControlHover(@Nullable ControlElement hovered, int mouseX, int mouseY);

    @Unique
    private @Nullable Identifier flywheel$previewTexture;
    @Unique
    private int flywheel$previewWidth;
    @Unique
    private int flywheel$previewHeight;
    @Unique
    private boolean flywheel$previewPending;

    // Reserve content rows; Sodium still computes the entire tooltip's geometry and scrollbar.
    @WrapOperation(method = "generateTooltipContent", at = @At(value = "INVOKE", ordinal = 0, target =
            "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"))
    private List<FormattedCharSequence> flywheel$includePreview(Font font, FormattedText text, int width,
                                                                Operation<List<FormattedCharSequence>> original) {
        var lines = original.call(font, text, width);
        var option = hoveredElement.getOption();
        flywheel$previewTexture = SodiumOptionTooltips.previewTexture(option, flywheel$candidate(option));
        if (flywheel$previewTexture == null) return lines;
        flywheel$previewWidth = Math.min(width, 160);
        flywheel$previewHeight = flywheel$previewWidth * SodiumOptionTooltips.SCENE_HEIGHT / SodiumOptionTooltips.SCENE_WIDTH;
        int stride = font.lineHeight + Layout.TEXT_LINE_SPACING;
        int rows = (flywheel$previewHeight + stride - 1) / stride;
        var content = new ArrayList<FormattedCharSequence>(rows + lines.size());
        for (int i = 0; i < rows; i++) content.add(FormattedCharSequence.EMPTY);
        content.addAll(lines);
        return content;
    }

    @Unique
    private @Nullable Object flywheel$candidate(Option option) {
        return parent instanceof SodiumDropdownView view ? view.flywheel$dropdownMenu().candidateValue(option) : null;
    }

    @WrapOperation(method = "generateTooltipContent", at = @At(value = "INVOKE", target =
            "Lnet/caffeinemc/mods/sodium/client/config/structure/Option;getTooltip()Lnet/minecraft/network/chat/Component;"))
    private Component flywheel$candidateDescription(Option option, Operation<Component> original) {
        var value = flywheel$candidate(option);
        return value == null ? original.call(option) : SodiumOptionTooltips.candidateDescription(option, value);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void flywheel$beginPreview(GuiGraphicsExtractor _, CallbackInfo _) {
        if (hoveredElement != null && !SodiumOptionTables.isVisible(hoveredElement.getOption()))
            onControlHover(null, -1, -1);
        flywheel$previewPending = flywheel$previewTexture != null;
    }

    // The first reserved line supplies Sodium's exact padding and scroll offset, inside its active scissor.
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"))
    private void flywheel$drawPreview(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text,
                                      int x, int y, int color, Operation<Void> original) {
        if (flywheel$previewPending) {
            flywheel$previewPending = false;
            graphics.blit(RenderPipelines.GUI_TEXTURED, flywheel$previewTexture, x, y, 0, 0,
                    flywheel$previewWidth, flywheel$previewHeight, SodiumOptionTooltips.SCENE_WIDTH,
                    SodiumOptionTooltips.SCENE_HEIGHT, SodiumOptionTooltips.SCENE_WIDTH,
                    SodiumOptionTooltips.TEXTURE_HEIGHT);
        }
        original.call(graphics, font, text, x, y, color);
    }
}
