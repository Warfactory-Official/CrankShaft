package dev.engine_room.vanillin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dev.engine_room.vanillin.config.*;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class VanillinFlwConfig {
    public static final VanillinFlwConfig INSTANCE = new VanillinFlwConfig();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String OVERRIDES_RESOURCE = "flywheel-overrides.json";

    private File file;
    private ModOverrides overrides;
    private Config config = new Config();

    private VanillinFlwConfig() {
    }

    // 1.12.2: deferred file init — Forge's config dir is only available in FMLPreInitializationEvent.
    public void load(Path configDir) {
        this.file = configDir.resolve("flywheel-vanilla.json").toFile();

        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                config = GSON.fromJson(reader, Config.class);
            } catch (Exception e) {
                Vanillin.CONFIG_LOGGER.warn("Could not load config from file '{}'", file.getAbsolutePath(), e);
            }
        }

        overrides = modOverrides();
    }

    public void apply(Configurator configurator) {
        Map<String, VisualConfigValue> blockEntities = config.blockEntities;
        Map<String, List<VisualOverride>> blockEntityOverrides = this.overrides.blockEntities();

        for (Configurator.ConfiguredVisual configured : configurator.blockEntities.values()) {
            apply(configured, blockEntities, blockEntityOverrides);
        }

        Map<String, VisualConfigValue> entities = config.entities;
        Map<String, List<VisualOverride>> entityOverrides = this.overrides.entities();
        for (Configurator.ConfiguredVisual configured : configurator.entities.values()) {
            apply(configured, entities, entityOverrides);
        }
    }

    private static void apply(Configurator.ConfiguredVisual configured, Map<String, VisualConfigValue> config, Map<String, List<VisualOverride>> overrides) {
        String key = configured.configKey();
        VisualConfigValue enabled = config.computeIfAbsent(key, $ -> VisualConfigValue.DEFAULT);

        configured.set(enabled, overrides.get(key));
    }

    public void save() {
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            Vanillin.CONFIG_LOGGER.warn("Could not save config to file '{}'", file.getAbsolutePath(), e);
        }
    }

    public static ModOverrides modOverrides() {
        List<VisualOverride> blockEntities = new ArrayList<>();
        List<VisualOverride> entities = new ArrayList<>();

        for (ModContainer container : Loader.instance().getActiveModList()) {
            String modid = container.getModId();
            File source = container.getSource();
            if (source == null) {
                continue;
            }

            OverrideFile parsed = readOverrideFile(modid, source);
            if (parsed == null) {
                continue;
            }

            readSection(blockEntities, modid, parsed.blockEntities, "block entity");
            readSection(entities, modid, parsed.entities, "entity");
        }

        return new ModOverrides(blockEntities, entities);
    }

    private static @Nullable OverrideFile readOverrideFile(String modid, File source) {
        String resourcePath = "assets/" + modid + "/" + OVERRIDES_RESOURCE;

        if (source.isDirectory()) {
            File f = new File(source, resourcePath);
            if (!f.isFile()) {
                return null;
            }
            try (FileReader reader = new FileReader(f)) {
                return GSON.fromJson(reader, OverrideFile.class);
            } catch (Exception e) {
                Vanillin.CONFIG_LOGGER.warn("Mod '{}': could not read {}", modid, f.getAbsolutePath(), e);
                return null;
            }
        }

        if (!source.isFile()) {
            return null;
        }
        try (ZipFile zip = new ZipFile(source)) {
            ZipEntry entry = zip.getEntry(resourcePath);
            if (entry == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, OverrideFile.class);
            }
        } catch (Exception e) {
            Vanillin.CONFIG_LOGGER.warn("Mod '{}': could not read {} from {}", modid, resourcePath, source.getAbsolutePath(), e);
            return null;
        }
    }

    private static void readSection(List<VisualOverride> dst, String modid, @Nullable Map<String, String> section, String singular) {
        if (section == null) {
            return;
        }
        for (Map.Entry<String, String> entry : section.entrySet()) {
            String key = entry.getKey();
            String valueString = entry.getValue();
            if (valueString == null) {
                Vanillin.CONFIG_LOGGER.warn("Mod '{}' attempted to override {} '{}' with a null value, ignoring", modid, singular, key);
                continue;
            }

            VisualOverrideValue parsed = VisualOverrideValue.parse(valueString);

            if (parsed == null) {
                Vanillin.CONFIG_LOGGER.warn("Mod '{}' attempted to override {} '{}' with an invalid value '{}', ignoring", modid, singular, key, valueString);
                continue;
            }

            dst.add(new VisualOverride(key, modid, parsed));
        }
    }

    public static class Config {
        @SerializedName("block_entities")
        public Map<String, VisualConfigValue> blockEntities;
        public Map<String, VisualConfigValue> entities;

        public Config() {
            // 1.12.2: linked for deterministic iteration order
            this(new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        public Config(Map<String, VisualConfigValue> blockEntities, Map<String, VisualConfigValue> entities) {
            this.blockEntities = blockEntities;
            this.entities = entities;
        }
    }

    private static class OverrideFile {
        @SerializedName("block_entities")
        @Nullable
        Map<String, String> blockEntities;
        @Nullable
        Map<String, String> entities;
    }
}
