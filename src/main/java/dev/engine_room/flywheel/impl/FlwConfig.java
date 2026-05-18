package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Loader;
import org.jspecify.annotations.Nullable;

import java.io.File;

public final class FlwConfig implements BackendConfig {
    public static final FlwConfig INSTANCE = new FlwConfig();
    public static final String DEFAULT_BACKEND_STR = "DEFAULT";

    private static final String CAT_CLIENT = "client";
    private static final String CAT_BACKENDS = "client.flw_backends";
    private static final String CAT_OPTIONS_UNIFORMS = "client.options_uniforms";

    private static final String K_BACKEND = "backend";
    private static final String K_LIMIT_UPDATES = "limitUpdates";
    private static final String K_WORKER_THREADS = "workerThreads";
    private static final String K_USE_COMMON_POOL = "useCommonPool";
    private static final String K_LIGHT_SMOOTHNESS = "lightSmoothness";
    private static final String K_EMISSIVE_BLOCK_AO_BAKING_FIX = "emissiveBlockAoBakingFix";

    private static final String K_DISTORTION = "distortion";
    private static final String K_GLINT_SPEED = "glintSpeed";
    private static final String K_GLINT_STRENGTH = "glintStrength";
    private static final String K_HIGH_CONTRAST = "highContrast";
    private static final String K_TEXT_BACKGROUND_OPACITY = "textBackgroundOpacity";
    private static final String K_TEXT_BACKGROUND_FOR_CHAT_ONLY = "textBackgroundForChatOnly";
    private static final String K_DARKNESS_PULSING = "darknessPulsing";
    private static final String K_DAMAGE_TILT = "damageTilt";
    private static final String K_HIDE_LIGHTNING_FLASHES = "hideLightningFlashes";

    private Configuration cfg;

    private String backendStr = DEFAULT_BACKEND_STR;
    private boolean limitUpdates = true;
    private int workerThreads = -1;
    private boolean useCommonPool = false;
    private LightSmoothness lightSmoothness = LightSmoothness.SMOOTH;
    private EmissiveBlockAoBakingFix emissiveBlockAoBakingFix = EmissiveBlockAoBakingFix.OFF;

    private float distortionOption = 1.0f;
    private float glintSpeedOption = 1.0f;
    private float glintStrengthOption = 1.0f;
    private boolean highContrastOption = false;
    private float textBackgroundOpacityOption = 0.5f;
    private boolean textBackgroundForChatOnlyOption = false;
    private float darknessPulsingOption = 1.0f;
    private float damageTiltOption = 1.0f;
    private boolean hideLightningFlashesOption = false;

    private FlwConfig() {
    }

    public void load(File configDir) {
        cfg = new Configuration(new File(configDir, "flywheel.cfg"));
        cfg.load();

        backendStr = cfg.getString(K_BACKEND, CAT_CLIENT, DEFAULT_BACKEND_STR,
                "Select the backend to use. Set to \"DEFAULT\" to let Flywheel decide.");
        limitUpdates = cfg.getBoolean(K_LIMIT_UPDATES, CAT_CLIENT, true,
                "Enable or disable instance update limiting with distance.");
        int processors = Runtime.getRuntime().availableProcessors();
        workerThreads = cfg.getInt(K_WORKER_THREADS, CAT_CLIENT, -1, -processors, processors,
                "Number of worker threads for the Flywheel ForkJoinPool. Positive: absolute count, "
                        + "clamped to availableProcessors. Zero or negative: relative to availableProcessors "
                        + "(0 = all cores, -1 = leaves one for the render thread, -N = leaves N). "
                        + "Result is always at least 1; a result of 1 routes to the serial executor. "
                        + "Requires a game restart to take effect.");
        useCommonPool = cfg.getBoolean(K_USE_COMMON_POOL, CAT_CLIENT, false,
                "If true, use the JVM-wide ForkJoinPool.commonPool() instead of a dedicated Flywheel pool. "
                        + "Saves threads but other code submitting to the common pool (incl. misbehaving mods) "
                        + "can stall Flywheel sync points. Requires a game restart to take effect.");
        String lsName = cfg.getString(K_LIGHT_SMOOTHNESS, CAT_BACKENDS, "SMOOTH",
                "How smooth Flywheel's shader-based lighting should be. May have a large performance impact.",
                enumNames(LightSmoothness.class));
        lightSmoothness = parseLightSmoothness(lsName);
        String aoName = cfg.getString(K_EMISSIVE_BLOCK_AO_BAKING_FIX, CAT_CLIENT, "AUTO",
                "Whether bakes apply AO to dim light-emitters (fixes MC-225516). "
                        + "AUTO matches the surrounding terrain — enabled iff Alfheim/Hesperus/Phosphor is installed. "
                        + "ON forces the fix; OFF keeps strict vanilla behavior.",
                new String[]{"AUTO", "ON", "OFF"});
        emissiveBlockAoBakingFix = parseEmissiveBlockAoBakingFix(aoName);

        String optsComment = "Values forwarded to the OptionsUniforms UBO for downstream shader use. "
                + "CrankShaft itself only consumes glintSpeed (foil item shimmer); the rest are "
                + "passthrough knobs for shaders that read 1.20+-era option uniforms.";
        distortionOption = cfg.getFloat(K_DISTORTION, CAT_OPTIONS_UNIFORMS, 1.0f, 0.0f, 1.0f,
                optsComment + " 1.0 = full screen-effect distortion (portal/pumpkin/nausea overlays).");
        glintSpeedOption = cfg.getFloat(K_GLINT_SPEED, CAT_OPTIONS_UNIFORMS, 1.0f, 0.0f, 1.0f,
                optsComment + " 1.0 = vanilla enchantment foil scroll speed; 0.0 = static.");
        glintStrengthOption = cfg.getFloat(K_GLINT_STRENGTH, CAT_OPTIONS_UNIFORMS, 1.0f, 0.0f, 1.0f,
                optsComment + " 1.0 = vanilla enchantment foil alpha.");
        highContrastOption = cfg.getBoolean(K_HIGH_CONTRAST, CAT_OPTIONS_UNIFORMS, false,
                optsComment + " High-contrast UI flag.");
        textBackgroundOpacityOption = cfg.getFloat(K_TEXT_BACKGROUND_OPACITY, CAT_OPTIONS_UNIFORMS, 0.5f, 0.0f, 1.0f,
                optsComment + " 0.5 mirrors vanilla 1.12.2's hard-coded chat-hover alpha.");
        textBackgroundForChatOnlyOption = cfg.getBoolean(K_TEXT_BACKGROUND_FOR_CHAT_ONLY, CAT_OPTIONS_UNIFORMS, false,
                optsComment + " If true, only chat shows a text background.");
        darknessPulsingOption = cfg.getFloat(K_DARKNESS_PULSING, CAT_OPTIONS_UNIFORMS, 1.0f, 0.0f, 1.0f,
                optsComment + " 1.0 = full sculk darkness-effect pulse.");
        damageTiltOption = cfg.getFloat(K_DAMAGE_TILT, CAT_OPTIONS_UNIFORMS, 1.0f, 0.0f, 1.0f,
                optsComment + " 1.0 = vanilla damage-tilt strength.");
        hideLightningFlashesOption = cfg.getBoolean(K_HIDE_LIGHTNING_FLASHES, CAT_OPTIONS_UNIFORMS, false,
                optsComment + " Accessibility flag for shaders that suppress lightning flashes.");

        if (cfg.hasChanged()) cfg.save();
    }

    public Backend backend() {
        Backend backend = parseBackend(backendStr);
        if (backend == null) {
            setBackendString(DEFAULT_BACKEND_STR);
            return BackendManager.defaultBackend();
        }
        return backend;
    }

    public boolean limitUpdates() {
        return limitUpdates;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int workerThreadCount() {
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = workerThreads <= 0 ? Math.max(1, processors + workerThreads) : Math.min(workerThreads, processors);
        return Math.max(1, workers);
    }

    public boolean useCommonPool() {
        return useCommonPool;
    }

    public BackendConfig backendConfig() {
        return this;
    }

    @Override
    public LightSmoothness lightSmoothness() {
        return lightSmoothness;
    }

    public EmissiveBlockAoBakingFix emissiveBlockAoBakingFix() {
        return emissiveBlockAoBakingFix;
    }

    public float distortionOption() {
        return distortionOption;
    }

    public float glintSpeedOption() {
        return glintSpeedOption;
    }

    public float glintStrengthOption() {
        return glintStrengthOption;
    }

    public boolean highContrastOption() {
        return highContrastOption;
    }

    public float textBackgroundOpacityOption() {
        return textBackgroundOpacityOption;
    }

    public boolean textBackgroundForChatOnlyOption() {
        return textBackgroundForChatOnlyOption;
    }

    public float darknessPulsingOption() {
        return darknessPulsingOption;
    }

    public float damageTiltOption() {
        return damageTiltOption;
    }

    public boolean hideLightningFlashesOption() {
        return hideLightningFlashesOption;
    }

    public void setBackendString(String value) {
        backendStr = value;
        if (cfg == null) return;
        cfg.get(CAT_CLIENT, K_BACKEND, DEFAULT_BACKEND_STR).set(value);
        if (cfg.hasChanged()) cfg.save();
    }

    public void setLimitUpdates(boolean v) {
        limitUpdates = v;
        if (cfg == null) return;
        cfg.get(CAT_CLIENT, K_LIMIT_UPDATES, true).set(v);
        if (cfg.hasChanged()) cfg.save();
    }

    public void setLightSmoothness(LightSmoothness v) {
        lightSmoothness = v;
        if (cfg == null) return;
        cfg.get(CAT_BACKENDS, K_LIGHT_SMOOTHNESS, "SMOOTH").set(v.name());
        if (cfg.hasChanged()) cfg.save();
    }

    public void setEmissiveBlockAoBakingFix(EmissiveBlockAoBakingFix v) {
        emissiveBlockAoBakingFix = v;
        if (cfg == null) return;
        cfg.get(CAT_CLIENT, K_EMISSIVE_BLOCK_AO_BAKING_FIX, "AUTO").set(v.name());
        if (cfg.hasChanged()) cfg.save();
    }

    private static LightSmoothness parseLightSmoothness(String name) {
        try {
            return LightSmoothness.valueOf(name);
        } catch (IllegalArgumentException e) {
            FlwImpl.CONFIG_LOGGER.warn("Unknown lightSmoothness value '{}', defaulting to SMOOTH", name);
            return LightSmoothness.SMOOTH;
        }
    }

    private static EmissiveBlockAoBakingFix parseEmissiveBlockAoBakingFix(String name) {
        if ("AUTO".equalsIgnoreCase(name)) {
            return (Loader.isModLoaded("alfheim") || Loader.isModLoaded("phosphor")) ? EmissiveBlockAoBakingFix.ON : EmissiveBlockAoBakingFix.OFF;
        }
        try {
            return EmissiveBlockAoBakingFix.valueOf(name);
        } catch (IllegalArgumentException e) {
            FlwImpl.CONFIG_LOGGER.warn("Unknown emissiveBlockAoBakingFix value '{}', resolving as AUTO", name);
            return (Loader.isModLoaded("alfheim") || Loader.isModLoaded("phosphor")) ? EmissiveBlockAoBakingFix.ON : EmissiveBlockAoBakingFix.OFF;
        }
    }

    private static @Nullable Backend parseBackend(String value) {
        if (value.equalsIgnoreCase(DEFAULT_BACKEND_STR)) {
            return BackendManager.defaultBackend();
        }
        String full = value.contains(":") ? value : Flywheel.ID + ":" + value;
        ResourceLocation backendId;
        try {
            backendId = new ResourceLocation(full);
        } catch (Exception e) {
            FlwImpl.CONFIG_LOGGER.warn("'backend' value '{}' is not a valid resource location", value);
            return null;
        }
        Backend backend = Backend.REGISTRY.get(backendId);
        if (backend == null) {
            FlwImpl.CONFIG_LOGGER.warn("Backend with ID '{}' is not registered", backendId);
            return null;
        }
        return backend;
    }

    private static String[] enumNames(Class<? extends Enum<?>> e) {
        Enum<?>[] vals = e.getEnumConstants();
        String[] names = new String[vals.length];
        for (int i = 0; i < vals.length; i++) names[i] = vals[i].name();
        return names;
    }
}
