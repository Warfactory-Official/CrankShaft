package dev.engine_room.vanillin.config;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.vanillin.Vanillin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.tileentity.TileEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Configurator {
    public final Map<Class<? extends TileEntity>, ConfiguredBlockEntity<?>> blockEntities = new HashMap<>();
    public final Map<Class<? extends Entity>, ConfiguredEntity<?>> entities = new HashMap<>();

    public <T extends TileEntity> void register(Class<T> type, BlockEntityVisualizer<? super T> visualizer, boolean enabledByDefault) {
        blockEntities.put(type, new ConfiguredBlockEntity<>(type, visualizer, enabledByDefault));
    }

    public <T extends Entity> void register(Class<T> type, EntityVisualizer<? super T> visualizer, boolean enabledByDefault) {
        entities.put(type, new ConfiguredEntity<>(type, visualizer, enabledByDefault));
    }

    public static abstract class ConfiguredVisual {
        private final boolean enabledByDefault;

        protected ConfiguredVisual(boolean enabledByDefault) {
            this.enabledByDefault = enabledByDefault;
        }

        public void set(VisualConfigValue configValue, @Nullable List<VisualOverride> overrides) {
            if (configValue == VisualConfigValue.DISABLE) {
                disable();
            } else if (configValue == VisualConfigValue.FORCE_ENABLE) {
                enable();
                maybeWarnEnabledDespiteOverrides(overrides);
            } else if (configValue == VisualConfigValue.DEFAULT) {
                if (disableAndWarnDueToOverrides(overrides)) {
                    disable();
                } else {
                    if (enabledByDefault) {
                        enable();
                    } else {
                        disable();
                    }
                }
            }
        }

        private boolean disableAndWarnDueToOverrides(@Nullable List<VisualOverride> overrides) {
            if (overrides == null || overrides.isEmpty()) {
                return false;
            }

            List<String> modIds = disablingModIds(overrides);

            if (modIds.isEmpty()) {
                return false;
            } else {
                Vanillin.CONFIG_LOGGER.warn("Disabling {} visual due to overrides from mods: {}", configKey(), String.join(", ", modIds));
                return true;
            }
        }

        private void maybeWarnEnabledDespiteOverrides(@Nullable List<VisualOverride> overrides) {
            if (overrides == null || overrides.isEmpty()) {
                return;
            }

            List<String> modIds = disablingModIds(overrides);

            if (!modIds.isEmpty()) {
                Vanillin.CONFIG_LOGGER.warn("Enabling {} visual despite overrides from mods: {}", configKey(), String.join(", ", modIds));
            }
        }

        public abstract String configKey();

        protected abstract void enable();

        protected abstract void disable();

        private static List<String> disablingModIds(List<VisualOverride> overrides) {
            List<String> out = new ArrayList<>();

            for (VisualOverride override : overrides) {
                if (override.value() == VisualOverrideValue.DISABLE) {
                    out.add(override.modId());
                }
            }
            return out;
        }
    }

    public static class ConfiguredBlockEntity<T extends TileEntity> extends ConfiguredVisual {
        public final Class<T> type;
        public final BlockEntityVisualizer<? super T> visualizer;

        private ConfiguredBlockEntity(Class<T> type, BlockEntityVisualizer<? super T> visualizer, boolean enabledByDefault) {
            super(enabledByDefault);
            this.type = type;
            this.visualizer = visualizer;
        }

        @Override
        public String configKey() {
            return TileEntity.getKey(type).toString();
        }

        @Override
        protected void enable() {
            VisualizerRegistry.setBlockEntityVisualizer(type, visualizer);
        }

        @Override
        protected void disable() {
            VisualizerRegistry.setBlockEntityVisualizer(type, null);
        }
    }

    public static class ConfiguredEntity<T extends Entity> extends ConfiguredVisual {
        public final Class<T> type;
        public final EntityVisualizer<? super T> visualizer;

        private ConfiguredEntity(Class<T> type, EntityVisualizer<? super T> visualizer, boolean enabledByDefault) {
            super(enabledByDefault);
            this.type = type;
            this.visualizer = visualizer;
        }

        @Override
        public String configKey() {
            return EntityList.getKey(type).toString();
        }

        @Override
        protected void enable() {
            VisualizerRegistry.setEntityVisualizer(type, visualizer);
        }

        @Override
        protected void disable() {
            VisualizerRegistry.setEntityVisualizer(type, null);
        }
    }
}
