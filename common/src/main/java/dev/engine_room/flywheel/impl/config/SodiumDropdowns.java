package dev.engine_room.flywheel.impl.config;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.BackendRecovery;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwConfig;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.config.structure.IntegerOption;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.gui.options.control.AbstractOptionList;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.options.control.StatefulControlElement;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Client-thread config registrations; popups edit the original Sodium binding and retain Apply/Undo.
 */
public final class SodiumDropdowns {
    private static List<Backend> configuredBackends;
    public static final Identifier BACKEND = Identifier.parse("crankshaft:backend");

    private SodiumDropdowns() {
    }

    public static OptionGroupBuilder backendGroup(ConfigBuilder builder, FlwConfig config,
                                                  StorageEventHandler storage, Identifier reload) {
        var backends = new ArrayList<>(Backend.REGISTRY.getAll());
        backends.sort(Comparator.comparingInt(Backend::priority).reversed()
                                .thenComparing(backend -> Backend.REGISTRY.getIdOrThrow(backend).toString()));
        configuredBackends = backends;
        var values = new ArrayList<String>();
        values.add(FlwConfig.DEFAULT_BACKEND_STR);
        backends.forEach(backend -> values.add(Backend.REGISTRY.getIdOrThrow(backend).toString()));
        SodiumOptionTables.registerControl(BACKEND, option -> {
            var integer = (IntegerOption) option;
            return new DropdownControl(new Model<>(integer, IntStream.range(0, values.size()).boxed().toList(),
                    integer::formatValue, index -> index == 0 || supported(backends.get(index - 1))));
        });
        return builder.createOptionGroup().addOption(builder.createIntegerOption(BACKEND)
                                                            .setName(SodiumConfig.text("backend"))
                                                            .setTooltip(index -> backendTooltip(values.get(index),
                                                                    index == 0 || supported(backends.get(index - 1))))
                                                            .setStorageHandler(storage).setDefaultValue(0)
                                                            .setImpact(OptionImpact.VARIES)
                                                            .setRange(0, values.size() - 1, 1)
                                                            .setValueFormatter(index -> backendName(values.get(index)))
                                                            .setBinding(
                                                                    index -> config.setBackendString(values.get(index)),
                                                                    () -> backendBinding(config, values, backends))
                                                            .setFlags(reload));
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<E>> void registerEnum(Identifier id, Class<E> type) {
        SodiumOptionTables.registerControl(id, option -> {
            var enumeration = (EnumOption<E>) option;
            return new DropdownControl(new Model<>(enumeration, Arrays.asList(type.getEnumConstants()),
                    enumeration::getElementName, enumeration::isValueAllowed));
        });
    }

    private static boolean supported(Backend backend) {
        return !BackendRecovery.isRejected(backend) && backend.isSupported();
    }

    private static int backendBinding(FlwConfig config, List<String> values, List<Backend> backends) {
        int index = Objects.checkIndex(values.indexOf(config.backendString()), values.size());
        if (index == 0 || supported(backends.get(index - 1))) return index;
        var active = BackendManagerImpl.currentBackend();
        return supported(active) ? values.indexOf(Backend.REGISTRY.getIdOrThrow(active).toString()) : 0;
    }

    /**
     * Resolves the staged UI preference without applying it or overwriting an unavailable saved preference.
     */
    public static Backend selectedBackend() {
        int index = ((IntegerOption) ConfigManager.CONFIG.getOption(BACKEND)).getValidatedValue();
        if (index != 0 && supported(configuredBackends.get(index - 1))) return configuredBackends.get(index - 1);
        for (var backend : configuredBackends)
            if (backend.automaticSelection() && supported(backend)) return backend;
        return BackendManagerImpl.OFF_BACKEND;
    }

    private static Component backendTooltip(String value, boolean available) {
        var key = "flywheel.options.backend." + (FlwConfig.DEFAULT_BACKEND_STR.equals(value) ? "automatic"
                : value.replace(':', '.')) + ".tooltip";
        var tooltip = Language.getInstance().has(key) ? Component.translatable(key) : SodiumConfig.text(
                "backend.tooltip");
        if (available) return tooltip;
        return tooltip.copy().append("\n")
                      .append(SodiumConfig.text("backend.unavailable").withStyle(ChatFormatting.GRAY));
    }

    private static Component backendName(String value) {
        return FlwConfig.DEFAULT_BACKEND_STR.equals(value) ? SodiumConfig.text("backend.automatic")
                : Component.translatableWithFallback("flywheel.options.backend." + value.replace(':', '.'), value);
    }

    static boolean valueAvailable(Option option, @Nullable Object value) {
        if (option.getControl() instanceof DropdownControl(Choices choices)) {
            return choices.enabled(choices.indexOf(value));
        }
        return true;
    }

    public interface Choices {
        StatefulOption<?> option();

        int size();

        int selected();

        void choose(int index);

        boolean enabled(int index);

        Component name(int index);

        Object value(int index);

        int indexOf(Object value);
    }

    private static final class Model<T> implements Choices {
        private final StatefulOption<T> option;
        private final List<T> values;
        private final Function<T, Component> names;
        private final Predicate<T> allowed;
        private final Component[] labels;
        private Language language;

        Model(StatefulOption<T> option, List<T> values, Function<T, Component> names, Predicate<T> allowed) {
            this.option = option;
            this.values = values;
            this.names = names;
            this.allowed = allowed;
            labels = new Component[values.size()];
        }

        @Override
        public StatefulOption<T> option() {
            return option;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public int selected() {
            var value = option.getValidatedValue();
            int selected = Objects.checkIndex(values.indexOf(value), values.size());
            return enabled(selected) ? selected : indexOf(SodiumOptionVisibility.effectiveValue(option, value));
        }

        @Override
        public void choose(int index) {
            option.modifyValue(values.get(index));
        }

        @Override
        public boolean enabled(int index) {
            return allowed.test(values.get(index)) && SodiumOptionVisibility.valueAvailable(option, values.get(index));
        }

        @Override
        public Component name(int index) {
            var language = Language.getInstance();
            if (this.language != language) {
                this.language = language;
                for (var i = 0; i < labels.length; i++) labels[i] = names.apply(values.get(i));
            }
            return labels[index];
        }

        @Override
        public Object value(int index) {
            return values.get(index);
        }

        @Override
        public int indexOf(Object value) {
            return Objects.checkIndex(values.indexOf(value), values.size());
        }
    }

    private record DropdownControl(Choices choices) implements Control {
        @Override
        public Option getOption() {
            return choices.option();
        }

        @Override
        public int getMaxWidth() {
            return 110;
        }

        @Override
        public ControlElement createElement(Screen screen, AbstractOptionList list, Dim2i dim, ColorTheme theme) {
            return new DropdownElement(screen, list, dim, theme, choices);
        }
    }

    private static final class DropdownElement extends StatefulControlElement {
        private final Screen screen;
        private final Choices choices;
        private Language language;
        private int contentWidth;
        private int width = -1;
        private int labelIndex = -1;
        private int labelWidth = -1;
        private String label;

        DropdownElement(Screen screen, AbstractOptionList list, Dim2i dim, ColorTheme theme, Choices choices) {
            super(list, dim, theme);
            this.screen = screen;
            this.choices = choices;
        }

        @Override
        public StatefulOption<?> getOption() {
            return choices.option();
        }

        @Override
        public int getContentWidth() {
            var language = Language.getInstance();
            if (this.language != language || width != getWidth()) {
                this.language = language;
                width = getWidth();
                contentWidth = Math.max(64, Math.min(width * 2 / 3, width - font.width(getOption().getName()) - 20));
                labelIndex = -1;
            }
            return contentWidth;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            if (getOption().shouldHideControl() || isResetOverlayActive()) return;
            var enabled = getOption().isEnabled();
            var selected = choices.selected();
            var labelWidth = getContentWidth() - 24;
            if (labelIndex != selected || this.labelWidth != labelWidth) {
                labelIndex = selected;
                this.labelWidth = labelWidth;
                label = truncateTextToFit(choices.name(selected).getString(), labelWidth);
            }
            drawString(graphics, label, getLimitX() - 24 - font.width(label),
                    getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET,
                    enabled ? Colors.FOREGROUND : Colors.FOREGROUND_DISABLED);
            drawString(graphics, "▼", getLimitX() - 14, getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET,
                    enabled ? theme.theme : Colors.FOREGROUND_DISABLED);
            if (enabled && isHovered()) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (super.mouseClicked(event, doubleClick)) return true;
            if (getOption().isEnabled() && event.button() == InputConstants.MOUSE_BUTTON_LEFT && isMouseOver(event.x(),
                    event.y())) {
                open();
                return true;
            }
            return false;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (isFocused() && getOption().isEnabled() && (event.isSelection() || event.isDown())) {
                open();
                return true;
            }
            return false;
        }

        private void open() {
            playClickSound();
            ((SodiumDropdownView) screen).flywheel$dropdownMenu().open(this, choices, theme);
        }
    }
}
