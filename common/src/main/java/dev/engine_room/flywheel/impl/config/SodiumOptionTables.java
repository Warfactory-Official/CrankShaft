package dev.engine_room.flywheel.impl.config;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.engine_room.flywheel.impl.mixin.sodium.SodiumOptionAccessor;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.gui.options.control.AbstractOptionList;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Client-thread registrations consumed by Sodium's cached controls and unfiltered list layout.
 */
public final class SodiumOptionTables {
    private static final Map<Identifier, Function<Option, Control>> CONTROLS = new HashMap<>();
    private static final Set<Identifier> FOLDS = new HashSet<>();
    private static final Map<Screen, Set<Identifier>> EXPANDED = new WeakHashMap<>();

    private SodiumOptionTables() {
    }

    /**
     * Config entrypoints register a control factory before the first settings screen is created.
     */
    public static void registerControl(Identifier id, Function<Option, Control> factory) {
        CONTROLS.put(id, factory);
    }

    public static OptionGroupBuilder foldedGroup(ConfigBuilder builder, Identifier id, Component name,
                                                 Component tooltip, int count) {
        FOLDS.add(id);
        registerControl(id, option -> new FoldControl(option, id, count));
        return builder.createOptionGroup().addOption(builder.createExternalButtonOption(id)
                                                            .setName(name).setTooltip(tooltip)
                                                            .setScreenConsumer(SodiumOptionTables::missingControl));
    }

    private static void missingControl(Screen _) {
        throw new IllegalStateException("Sodium table control was not installed");
    }

    public static Identifier optionId(Option option) {
        return ((SodiumOptionAccessor) option).flywheel$id();
    }

    public static Set<Identifier> expandedFor(Screen screen) {
        return EXPANDED.computeIfAbsent(screen, _ -> new HashSet<>());
    }

    /**
     * Only unfiltered layout hides children; search, reset and persistence retain the original groups.
     */
    public static List<Option> visibleOptions(List<Option> options, Set<Identifier> expanded) {
        var visible = options.stream().filter(SodiumOptionTables::isVisible).toList();
        if (visible.isEmpty()) return visible;
        var id = optionId(visible.getFirst());
        return FOLDS.contains(id) && !expanded.contains(id) ? visible.subList(0, 1) : visible;
    }

    /**
     * Used by both page layout and search so inactive pack controls never appear as search results.
     */
    public static boolean isVisible(Option option) {
        return SodiumOptionVisibility.isVisible(option);
    }

    public static @Nullable Control createControl(Identifier id, Option option) {
        var factory = CONTROLS.get(id);
        return factory == null ? null : factory.apply(option);
    }

    private record FoldControl(Option option, Identifier id, int count) implements Control {
        @Override
        public Option getOption() {
            return option;
        }

        @Override
        public int getMaxWidth() {
            return Layout.CYCLING_CONTROL_WIDTH;
        }

        @Override
        public ControlElement createElement(Screen _, AbstractOptionList list, Dim2i dim, ColorTheme theme) {
            return new FoldElement(list, dim, theme, option, id, count);
        }
    }

    private static final class FoldElement extends ControlElement {
        private final Option option;
        private final Identifier id;
        private final SodiumTableView view;
        private final Component expandedText;
        private final Component foldedText;
        private Language language;
        private int expandedWidth;
        private int foldedWidth;

        FoldElement(AbstractOptionList list, Dim2i dim, ColorTheme theme, Option option, Identifier id, int count) {
            super(list, dim, theme);
            this.option = option;
            this.id = id;
            view = (SodiumTableView) list;
            expandedText = Component.literal("▼ ").append(SodiumConfig.text("table.entries", count));
            foldedText = Component.literal("▶ ").append(SodiumConfig.text("table.entries", count));
        }

        @Override
        public Option getOption() {
            return option;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            var language = Language.getInstance();
            if (this.language != language) {
                this.language = language;
                expandedWidth = font.width(expandedText);
                foldedWidth = font.width(foldedText);
            }
            var expanded = view.flywheel$isExpanded(id);
            var text = expanded ? expandedText : foldedText;
            var width = expanded ? expandedWidth : foldedWidth;
            drawString(graphics, text, getLimitX() - width - Layout.OPTION_TEXT_SIDE_PADDING,
                    getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET, Colors.FOREGROUND);
            if (isHovered()) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean _) {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || !isMouseOver(event.x(), event.y())) return false;
            toggle();
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!isFocused()) return false;
            if (event.isSelection() || event.isRight() && !view.flywheel$isExpanded(id)
                    || event.isLeft() && view.flywheel$isExpanded(id)) {
                toggle();
                return true;
            }
            return false;
        }

        private void toggle() {
            playClickSound();
            view.flywheel$toggleTable(id);
        }
    }
}
