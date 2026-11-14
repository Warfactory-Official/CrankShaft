package dev.engine_room.vanillin.config;

/**
 * Client-thread editing of the loader's saved visual preferences; flush once after a batch of changes.
 */
public interface VanillinConfig {
    boolean enabled();

    void setEnabled(boolean value);

    boolean configurable(boolean blockEntity, String key);

    VisualConfigValue visual(boolean blockEntity, String key);

    void setVisual(boolean blockEntity, String key, VisualConfigValue value);

    void saveAndApply();
}
