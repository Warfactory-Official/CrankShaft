package dev.engine_room.flywheel.impl.config;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.widgets.ScrollbarWidget;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Screen-local dropdown input and drawing; tooltip presentation belongs to Sodium.
 */
public final class SodiumDropdownMenu {
    private final Screen screen;
    private final Font font = Minecraft.getInstance().font;
    private final int rowHeight = Layout.entryHeight(font);
    private @Nullable Popup popup;
    private int mouseX;
    private int mouseY;

    public SodiumDropdownMenu(Screen screen) {
        this.screen = screen;
    }

    private static void border(GuiGraphicsExtractor graphics, Dim2i box, int color) {
        graphics.fill(box.x(), box.y(), box.getLimitX(), box.y() + 1, color);
        graphics.fill(box.x(), box.getLimitY() - 1, box.getLimitX(), box.getLimitY(), color);
        graphics.fill(box.x(), box.y(), box.x() + 1, box.getLimitY(), color);
        graphics.fill(box.getLimitX() - 1, box.y(), box.getLimitX(), box.getLimitY(), color);
    }

    public boolean isOpen() {
        return popup != null;
    }

    public void close() {
        popup = null;
    }

    public void open(ControlElement owner, SodiumDropdowns.Choices choices, ColorTheme theme) {
        popup = new Popup(owner, choices, theme);
    }

    /**
     * Candidate content keeps the real option's stock tooltip anchor, including near the action buttons.
     */
    public @Nullable ControlElement tooltipTarget(@Nullable ControlElement original) {
        if (popup == null) return original;
        boolean changed = popup.tooltipIndex != popup.highlight;
        if (changed || popup.keyboardNavigation || popup.bounds.containsCursor(mouseX, mouseY)
                || popup.owner.isMouseOver(mouseX, mouseY)) {
            popup.tooltipIndex = popup.highlight;
            return popup.owner;
        }
        return null;
    }

    /**
     * A hovered or keyboard-highlighted value is descriptive only; selection still goes through choose.
     */
    public @Nullable Object candidateValue(Option option) {
        return popup != null && popup.choices.option() == option
                ? popup.choices.value(popup.entries[popup.highlight]) : null;
    }

    public void updateHover(int x, int y) {
        var moved = x != mouseX || y != mouseY;
        mouseX = x;
        mouseY = y;
        if (popup != null && moved) {
            popup.keyboardNavigation = false;
            popup.updateHighlight(x, y);
        }
    }

    public boolean mouseClicked(MouseButtonEvent event) {
        if (popup == null) return false;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            close();
            return true;
        }
        if (popup.visible < popup.entries.length && popup.bounds.containsCursor(event.x(), event.y())
                && event.x() >= popup.bounds.getLimitX() - 7) {
            var fraction = Math.clamp((event.y() - popup.bounds.y() - 3) / (popup.bounds.height() - 6), 0.0, 1.0);
            popup.first = (int) Math.round(fraction * (popup.entries.length - popup.visible));
            popup.updateHighlight(event.x(), event.y());
            return true;
        }
        var row = popup.rowAt(event.x(), event.y());
        if (row >= 0) {
            popup.choices.choose(popup.entries[row]);
            close();
        } else close();
        return true;
    }

    public boolean keyPressed(KeyEvent event) {
        if (popup == null) return false;
        if (event.isEscape()) close();
        else if (event.isSelection()) {
            popup.choices.choose(popup.entries[popup.highlight]);
            close();
        } else if (event.isUp() || event.isDown()) popup.move(event.isDown() ? 1 : -1);
        else if (event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
            popup.highlight = event.key() == InputConstants.KEY_HOME ? popup.entries.length - 1 : 0;
            popup.move(event.key() == InputConstants.KEY_HOME ? 1 : -1);
        } else if (event.key() == InputConstants.KEY_TAB) {
            close();
            return false;
        }
        return true;
    }

    public boolean mouseScrolled(double x, double y, double amount) {
        if (popup == null) return false;
        if (popup.bounds.containsCursor(x, y)) {
            popup.keyboardNavigation = false;
            popup.first = Math.clamp(popup.first - (int) Math.signum(amount), 0, popup.entries.length - popup.visible);
            popup.updateHighlight(x, y);
            return true;
        }
        close();
        return false;
    }

    public Dim2i dropdownBounds() {
        return popup.bounds;
    }

    public void draw(GuiGraphicsExtractor graphics) {
        if (popup == null) return;
        if (!popup.owner.getOption().isEnabled()) {
            close();
            return;
        }
        graphics.nextStratum();
        popup.updateLabels();
        var box = popup.bounds;
        graphics.fill(box.x(), box.y(), box.getLimitX(), box.getLimitY(), Colors.BACKGROUND_DEFAULT);
        graphics.enableScissor(box.x() + 1, box.y() + 1, box.getLimitX() - 1, box.getLimitY() - 1);
        var selected = popup.choices.selected();
        for (var row = 0; row < popup.visible; row++) {
            var index = popup.first + row;
            var y = box.y() + Layout.OPTION_GROUP_MARGIN + row * rowHeight;
            if (index == popup.highlight) {
                graphics.fill(box.x(), y, box.getLimitX(), y + rowHeight, Colors.BACKGROUND_HOVER);
            }
            graphics.text(font, popup.labels[index], box.x() + Layout.OPTION_TEXT_SIDE_PADDING,
                    y + rowHeight / 2 + Layout.REGULAR_TEXT_BASELINE_OFFSET,
                    Colors.FOREGROUND);
            if (popup.entries[index] == selected) {
                int size = Layout.CONTROL_ICON_SIZE;
                int x = box.getLimitX() - Layout.OPTION_TEXT_SIDE_PADDING - size;
                int top = y + (rowHeight - size) / 2;
                var color = popup.theme.theme;
                border(graphics, new Dim2i(x, top, size, size), color);
                graphics.fill(x + 2, top + 2, x + size - 2, top + size - 2, color);
            }
        }
        if (popup.visible < popup.entries.length) {
            popup.scrollbar.scrollTo(popup.first * rowHeight);
            popup.scrollbar.extractRenderState(graphics, mouseX, mouseY, 0);
        }
        graphics.disableScissor();
        var hovered = popup.rowAt(mouseX, mouseY);
        if (hovered >= 0) graphics.requestCursor(CursorTypes.POINTING_HAND);
    }

    private final class Popup {
        final ControlElement owner;
        final SodiumDropdowns.Choices choices;
        final ColorTheme theme;
        final ScrollbarWidget scrollbar;
        final int[] entries;
        final Dim2i bounds;
        final int visible;
        final String[] labels;
        Language language;
        int first;
        int highlight;
        boolean keyboardNavigation;
        int tooltipIndex = -1;

        Popup(ControlElement owner, SodiumDropdowns.Choices choices, ColorTheme theme) {
            this.owner = owner;
            this.choices = choices;
            this.theme = theme;
            entries = IntStream.range(0, choices.size()).filter(choices::enabled).toArray();
            labels = new String[entries.length];
            var width = owner.getContentWidth();
            for (var index : entries) {
                width = Math.max(width, font.width(choices.name(index))
                        + 3 * Layout.OPTION_TEXT_SIDE_PADDING + Layout.CONTROL_ICON_SIZE);
            }
            width = Math.min(width, screen.width - 16);
            var below = screen.height - owner.getLimitY() - 10;
            var above = owner.getY() - 10;
            var cap = 8;
            var downward = below >= Math.min(entries.length, cap) * rowHeight || below >= above;
            visible = Math.min(entries.length,
                    Math.max(1, Math.min(cap, ((downward ? below : above) - 6) / rowHeight)));
            var height = visible * rowHeight + 6;
            var x = Math.clamp(owner.getLimitX() - width - 2, 8, screen.width - width - 8);
            var y = downward ? owner.getLimitY() + 3 : owner.getY() - height - 3;
            bounds = new Dim2i(x, Math.clamp(y, 4, screen.height - height - 4), width, height);
            scrollbar = new ScrollbarWidget(new Dim2i(bounds.getLimitX() - Layout.SCROLLBAR_WIDTH,
                    bounds.y() + Layout.OPTION_GROUP_MARGIN, Layout.SCROLLBAR_WIDTH, visible * rowHeight),
                    false, true);
            scrollbar.setScrollbarContext(visible * rowHeight, entries.length * rowHeight);
            highlight = Arrays.binarySearch(entries, choices.selected());
            ensureVisible();
        }

        int rowAt(double x, double y) {
            if (!bounds.containsCursor(x, y) || y < bounds.y() + 3 || y >= bounds.getLimitY() - 3) return -1;
            return first + Math.min(visible - 1, (int) (y - bounds.y() - 3) / rowHeight);
        }

        void updateHighlight(double x, double y) {
            var row = rowAt(x, y);
            if (row >= 0) highlight = row;
        }

        void updateLabels() {
            var language = Language.getInstance();
            if (this.language == language) return;
            this.language = language;
            for (var index = 0; index < labels.length; index++) {
                labels[index] = font.plainSubstrByWidth(choices.name(entries[index]).getString(), bounds.width()
                        - 3 * Layout.OPTION_TEXT_SIDE_PADDING - Layout.CONTROL_ICON_SIZE);
            }
        }

        void move(int direction) {
            keyboardNavigation = true;
            highlight = Math.floorMod(highlight + direction, entries.length);
            ensureVisible();
        }

        void ensureVisible() {
            first = Math.clamp(first, Math.max(0, highlight - visible + 1),
                    Math.min(highlight, entries.length - visible));
        }
    }
}
