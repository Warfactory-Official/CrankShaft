package dev.engine_room.flywheel.impl.mixin.sodium;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.impl.config.SodiumOptionTables;

import dev.engine_room.flywheel.impl.config.SodiumOptionVisibility;
import dev.engine_room.flywheel.impl.config.SodiumTableView;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionGroup;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.gui.options.control.AbstractOptionList;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

@Mixin(OptionListWidget.class)
abstract class SodiumOptionListMixin extends AbstractOptionList implements SodiumTableView {
    @Unique
    private Screen flywheel$screen;
    @Unique
    private Set<Identifier> flywheel$expanded;
    @Unique
    private boolean flywheel$layoutDirty;
    @Unique
    private Identifier flywheel$focusAfterLayout;

    protected SodiumOptionListMixin(Dim2i dim) {
        super(dim);
    }

    @Inject(method = "rebuild", at = @At("HEAD"))
    private void flywheel$screenState(Screen screen, CallbackInfo _) {
        flywheel$screen = screen;
        flywheel$expanded = SodiumOptionTables.expandedFor(screen);
    }

    // Only the unfiltered layout hides children. Config registration, reset and search use the full group.
    @WrapOperation(method = "renderAllPages", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/config/structure/OptionGroup;options()Ljava/util/List;"))
    private List<Option> flywheel$visibleRows(OptionGroup group, Operation<List<Option>> original) {
        return SodiumOptionTables.visibleOptions(original.call(group), flywheel$expanded);
    }

    @WrapOperation(method = "renderAllPages", at = @At(value = "INVOKE", target =
            "Lnet/caffeinemc/mods/sodium/client/config/structure/ModOptions;pages()Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<Page> flywheel$visiblePages(ModOptions mod, Operation<ImmutableList<Page>> original) {
        return SodiumOptionVisibility.pages(original.call(mod));
    }

    @WrapOperation(method = "renderAllPages", at = @At(value = "INVOKE", target =
            "Lnet/caffeinemc/mods/sodium/client/config/structure/Page;groups()Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<OptionGroup> flywheel$visibleGroups(Page page,
                                                              Operation<ImmutableList<OptionGroup>> original) {
        return SodiumOptionVisibility.groups(original.call(page));
    }

    @ModifyExpressionValue(method = "renderFilteredOptions", at = @At(value = "FIELD", target =
            "Lnet/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget;filteredOptions:Ljava/util/List;"))
    private List<Option.OptionNameSource> flywheel$visibleSearchRows(List<Option.OptionNameSource> options) {
        return options.stream().filter(source -> SodiumOptionTables.isVisible(source.getOption())).toList();
    }

    @Override
    public boolean flywheel$isExpanded(Identifier id) {
        return flywheel$expanded.contains(id);
    }

    @Override
    public void flywheel$toggleTable(Identifier id) {
        if (!flywheel$expanded.remove(id)) {
            flywheel$expanded.add(id);
        }
        flywheel$focusAfterLayout = id;
        flywheel$layoutDirty = true;
    }

    @Override
    public void flywheel$restoreScroll(int scroll) {
        scrollbar.scrollTo(scroll);
    }

    // Defer rebuilding until input dispatch finishes, so the old child/focus iteration cannot overwrite it.
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void flywheel$layoutTables(GuiGraphicsExtractor _, int _, int _, float _,
                                       CallbackInfo _) {
        if (!flywheel$layoutDirty) return;
        flywheel$layoutDirty = false;
        var scroll = getScrollAmount();
        setFocused(null);
        ((OptionListWidget) (Object) this).rebuild(flywheel$screen);
        scrollbar.scrollTo(scroll);
        if (flywheel$focusAfterLayout != null) controls.stream()
                                                       .filter(control -> flywheel$focusAfterLayout.equals(
                                                               SodiumOptionTables.optionId(control.getOption())))
                                                       .findFirst().ifPresent(control -> {
                    if (flywheel$expanded.contains(flywheel$focusAfterLayout)) {
                        var firstRowsBottom = control.getY() + Math.min(getHeight(), control.getHeight() * 4);
                        if (firstRowsBottom > getLimitY())
                            scrollbar.scrollTo(getScrollAmount() + firstRowsBottom - getLimitY());
                    }
                    setFocused(control);
                });
    }
}
