package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import net.minecraft.IdentifierException;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;

public final class NeoForgeFlwConfig implements FlwConfig, BackendConfig {
    public static final NeoForgeFlwConfig INSTANCE = new NeoForgeFlwConfig();

    public final ClientConfig client;
    private final ModConfigSpec clientSpec;

    private NeoForgeFlwConfig() {
        Pair<ClientConfig, ModConfigSpec> clientPair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        this.client = clientPair.getLeft();
        this.clientSpec = clientPair.getRight();
        OitConfig.setSaver(this::saveOit);
    }

    @Override
    public Backend backend() {
        Backend backend = parseBackend(client.backend.get());
        if (backend == null) {
            client.backend.set(DEFAULT_BACKEND_STR);
            return BackendManager.defaultBackend();
        }
        return backend;
    }

    @Override
    public boolean limitUpdates() {
        return client.limitUpdates.get();
    }

    public int workerThreads() {
        return client.workerThreads.get();
    }

    @Override
    public int workerThreadCount() {
        int workerThreads = client.workerThreads.get();
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = workerThreads <= 0 ? Math.max(1, processors + workerThreads) : Math.min(workerThreads, processors);
        return Math.max(1, workers);
    }

    @Override
    public boolean useCommonPool() {
        return client.useCommonPool.get();
    }

    @Override
    public BackendConfig backendConfig() {
        return this;
    }

    @Override
    public LightSmoothness lightSmoothness() {
        return client.lightSmoothness.get();
    }

    @Override
    public TerrainMode terrainMode() {
        return TerrainModeGate.effective(client.terrainMode.get());
    }

    @Override
    public boolean ownGeometry() {
        return client.ownGeometry.get();
    }

    public void setBackendString(String value) {
        client.backend.set(value);
        clientSpec.save();
    }

    public void setLimitUpdates(boolean v) {
        client.limitUpdates.set(v);
        clientSpec.save();
    }

    public void setLightSmoothness(LightSmoothness v) {
        client.lightSmoothness.set(v);
        clientSpec.save();
    }

    public void setTerrainMode(TerrainMode v) {
        client.terrainMode.set(v);
        clientSpec.save();
    }

    public void setOwnGeometry(boolean v) {
        client.ownGeometry.set(v);
        clientSpec.save();
    }

    public void saveOit() {
        client.oitPath.set(OitConfig.path());
        client.oitLayersKbuffer.set(OitConfig.rawLayers(OitConfig.Path.KBUFFER));
        client.oitLayersMlab.set(OitConfig.rawLayers(OitConfig.Path.MLAB));
        client.oitLayersAbuffer.set(OitConfig.rawLayers(OitConfig.Path.ABUFFER));
        client.oitExactWeather.set(OitConfig.exactFabulous());
        clientSpec.save();
    }

    public void syncOitToRuntime() {
        OitConfig.loadState(client.oitPath.get(), client.oitLayersKbuffer.get(),
                client.oitLayersMlab.get(), client.oitLayersAbuffer.get(), client.oitExactWeather.get());
    }

    public void registerSpecs(ModContainer context) {
        context.registerConfig(ModConfig.Type.CLIENT, clientSpec);
    }

    private static @Nullable Backend parseBackend(String value) {
        if (value.equalsIgnoreCase(DEFAULT_BACKEND_STR)) {
            return BackendManager.defaultBackend();
        }
        Identifier backendId;
        try {
            backendId = Identifier.parse(value);
        } catch (IdentifierException e) {
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

    public static final class ClientConfig {
        public final ModConfigSpec.ConfigValue<String> backend;
        public final ModConfigSpec.BooleanValue limitUpdates;
        public final ModConfigSpec.IntValue workerThreads;
        public final ModConfigSpec.BooleanValue useCommonPool;
        public final ModConfigSpec.EnumValue<LightSmoothness> lightSmoothness;
        public final ModConfigSpec.EnumValue<TerrainMode> terrainMode;
        public final ModConfigSpec.BooleanValue ownGeometry;
        public final ModConfigSpec.EnumValue<OitConfig.Path> oitPath;
        public final ModConfigSpec.IntValue oitLayersKbuffer;
        public final ModConfigSpec.IntValue oitLayersMlab;
        public final ModConfigSpec.IntValue oitLayersAbuffer;
        public final ModConfigSpec.BooleanValue oitExactWeather;

        private ClientConfig(ModConfigSpec.Builder builder) {
            backend = builder.comment("Select the backend to use. Set to \"DEFAULT\" to let Flywheel decide.")
                    .define("backend", DEFAULT_BACKEND_STR);

            limitUpdates = builder.comment("Enable or disable instance update limiting with distance.")
                    .define("limitUpdates", true);

            workerThreads = builder.comment("Number of worker threads for the Flywheel ForkJoinPool. "
                            + "Positive: absolute count, clamped to availableProcessors. Zero or negative: relative to "
                            + "availableProcessors (0 = all cores, -1 = leaves one for the render thread, -N = leaves N). "
                            + "Result is always at least 1; a result of 1 routes to the serial executor. "
                            + "Requires a game restart to take effect.")
                    .defineInRange("workerThreads", -1, -Runtime.getRuntime()
                            .availableProcessors(), Runtime.getRuntime()
                            .availableProcessors());

            useCommonPool = builder.comment("If true, use the JVM-wide ForkJoinPool.commonPool() instead of a dedicated "
                            + "Flywheel pool. Saves threads but other code submitting to the common pool (incl. "
                            + "misbehaving mods) can stall Flywheel sync points. Requires a game restart to take effect.")
                    .define("useCommonPool", false);

            builder.comment("Config options for Flywheel's built-in backends.")
                    .push("flw_backends");

            lightSmoothness = builder.comment("How smooth Flywheel's shader-based lighting should be. May have a large performance impact.")
                    .defineEnum("lightSmoothness", LightSmoothness.SMOOTH);

            terrainMode = builder.comment("How much chunk terrain Flywheel takes over. OFF: vanilla/Sodium draws "
                            + "everything. TRANSLUCENT_OIT: composite only the translucent layer through Flywheel's "
                            + "order-independent transparency so translucent instances sort against translucent terrain "
                            + "(any backend, Sodium or vanilla). OPAQUE: take over only OPAQUE terrain (solid + cutout) "
                            + "via GPU-driven MDI, leaving translucent to Sodium with no terrain OIT -- the "
                            + "culling-benchmark mode; requires Sodium and a gpu-driven backend, else it falls back to "
                            + "OFF. FULL: take over OPAQUE terrain plus translucent OIT -- requires Sodium and a "
                            + "gpu-driven backend, else it falls back to TRANSLUCENT_OIT.")
                    .defineEnum("terrain", TerrainMode.OFF);

            ownGeometry = builder.comment("Mesh-shader terrain tiers: copy Sodium's live geometry arena into a "
                            + "mod-owned device-local buffer (true) instead of aliasing it in place (false, the "
                            + "zero-repack default). Only affects gl_mesh_shader / vk_mesh_shader; ~2x terrain VRAM. "
                            + "A runtime change (/flywheel ownGeometry) applies on the next renderer reload.")
                    .define("ownGeometry", false);

            oitPath = builder.comment("Order-independent transparency path. AUTO: best available (MLAB on interlock "
                            + "hardware, else the wavelet chain). WAVELET: the multi-pass moment/wavelet chain. "
                            + "KBUFFER/MLAB/ABUFFER: single-geometry-pass insert strategies (MLAB/ABUFFER require "
                            + "fragment-shader interlock / atomics; fall back to wavelet otherwise).")
                    .defineEnum("oitPath", OitConfig.Path.AUTO);

            builder.comment("Translucent-layer budget per insert path (0 = the mode's preset: k-buffer 4, MLAB 8, "
                    + "A-buffer 16). For k-buffer/MLAB the sample count; for the A-buffer the resolve's nearest-N cap.");
            oitLayersKbuffer = builder.defineInRange("oitLayersKbuffer", 0, 0, OitConfig.MAX_LAYERS);
            oitLayersMlab = builder.defineInRange("oitLayersMlab", 0, 0, OitConfig.MAX_LAYERS);
            oitLayersAbuffer = builder.defineInRange("oitLayersAbuffer", 0, 0, OitConfig.MAX_LAYERS);

            oitExactWeather = builder.comment("Weather accuracy under OIT, all paths. false: rain/snow render once "
                            + "into a resolved layer (vanilla-fabulous semantics; far cheaper in rain). true: rain/snow "
                            + "are per-fragment OIT producers that depth-sort exactly against other translucents.")
                    .define("oitExactWeather", false);

            builder.pop();
        }
    }
}
