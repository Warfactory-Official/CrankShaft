package dev.engine_room.flywheel.impl;

import com.google.gson.*;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.ShaderCompilationMode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.IdentifierException;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;

import java.io.File;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;

public final class FabricFlwConfig implements FlwConfig {
    public static final Path PATH = FabricLoader.getInstance()
                                                .getConfigDir()
                                                .resolve("crankshaft.json");
    public static final boolean LIMIT_UPDATES_DEFAULT = true;
    public static final int WORKER_THREADS_DEFAULT = -1;
    public static final boolean USE_COMMON_POOL_DEFAULT = false;
    public static final boolean CONCURRENT_EXTRACTION_DEFAULT = true;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
                                                      .create();
    public static final int WORKER_THREADS_MIN = -Runtime.getRuntime()
                                                         .availableProcessors();
    public static final int WORKER_THREADS_MAX = Runtime.getRuntime()
                                                        .availableProcessors();

    public static final FabricFlwConfig INSTANCE = new FabricFlwConfig(PATH.toFile());

    private final File file;
    public Backend backend = BackendManager.offBackend();
    public boolean useDefaultBackend = true;
    public boolean limitUpdates = LIMIT_UPDATES_DEFAULT;
    public int workerThreads = WORKER_THREADS_DEFAULT;
    public boolean useCommonPool = USE_COMMON_POOL_DEFAULT;
    public boolean concurrentExtraction = CONCURRENT_EXTRACTION_DEFAULT;
    public final FabricBackendConfig backendConfig = new FabricBackendConfig();
    private boolean batching;
    private boolean pendingSave;

    public FabricFlwConfig(File file) {
        this.file = file;
        OitConfig.setSaver(this::save);
    }

    @Override
    public Backend backend() {
        if (useDefaultBackend) {
            return BackendManager.defaultBackend();
        }

        return backend;
    }

    @Override
    public String backendString() {
        return useDefaultBackend ? DEFAULT_BACKEND_STR : Backend.REGISTRY.getIdOrThrow(backend).toString();
    }

    @Override
    public boolean limitUpdates() {
        return limitUpdates;
    }

    @Override
    public int workerThreads() {
        return workerThreads;
    }

    @Override
    public int workerThreadCount() {
        int processors = Runtime.getRuntime()
                                .availableProcessors();
        int workers = workerThreads <= 0 ? Math.max(1, processors + workerThreads) : Math.min(workerThreads,
                processors);
        return Math.max(1, workers);
    }

    @Override
    public boolean useCommonPool() {
        return useCommonPool;
    }

    @Override
    public boolean concurrentExtraction() {
        return concurrentExtraction;
    }

    @Override
    public BackendConfig backendConfig() {
        return backendConfig;
    }

    @Override
    public void setBackendString(String value) {
        if (value.equals(DEFAULT_BACKEND_STR)) {
            backend = BackendManager.offBackend();
            useDefaultBackend = true;
        } else {
            backend = Backend.REGISTRY.getOrThrow(Identifier.parse(value));
            useDefaultBackend = false;
        }
        save();
    }

    @Override
    public void setLimitUpdates(boolean value) {
        limitUpdates = value;
        save();
    }

    @Override
    public void setWorkerThreads(int value) {
        workerThreads = value;
        save();
    }

    @Override
    public void setUseCommonPool(boolean value) {
        useCommonPool = value;
        save();
    }

    @Override
    public void setConcurrentExtraction(boolean value) {
        concurrentExtraction = value;
        save();
    }

    @Override
    public void setLightSmoothness(LightSmoothness value) {
        backendConfig.lightSmoothness = value;
        save();
    }

    @Override
    public void setTerrainMode(TerrainMode value) {
        backendConfig.terrainMode = value;
        save();
    }


    public void load() {
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                fromJson(JsonParser.parseReader(reader));
            } catch (Exception e) {
                FlwImpl.CONFIG_LOGGER.warn("Could not load config from file '{}'", file.getAbsolutePath(), e);
            }
        }
        // In case we found an error in the config file, immediately save to fix it.
        save();
    }

    public void save() {
        if (batching) {
            pendingSave = true;
            return;
        }
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(toJson(), writer);
        } catch (Exception e) {
            FlwImpl.CONFIG_LOGGER.warn("Could not save config to file '{}'", file.getAbsolutePath(), e);
        }
    }

    @Override
    public void beginBatch() {
        batching = true;
    }

    @Override
    public void endBatch(boolean persist) {
        batching = false;
        var save = pendingSave && persist;
        pendingSave = false;
        if (save) save();
    }

    public void fromJson(JsonElement json) {
        JsonObject object;
        if (json instanceof JsonObject config) {
            object = config;
        } else {
            FlwImpl.CONFIG_LOGGER.warn("Config JSON must be an object");
            object = new JsonObject();
        }

        readBackend(object);
        limitUpdates = readBoolean(object, "limitUpdates", LIMIT_UPDATES_DEFAULT);
        readWorkerThreads(object);
        useCommonPool = readBoolean(object, "useCommonPool", USE_COMMON_POOL_DEFAULT);
        concurrentExtraction = readBoolean(object, "concurrentExtraction", CONCURRENT_EXTRACTION_DEFAULT);
        readFlwBackends(object);
    }

    private void readBackend(JsonObject object) {
        var backendJson = object.get("backend");
        String msg = null;

        if (backendJson instanceof JsonPrimitive primitive && primitive.isString()) {
            var value = primitive.getAsString();
            if (value.equals(DEFAULT_BACKEND_STR)) {
                backend = BackendManager.offBackend();
                useDefaultBackend = true;
                return;
            }

            try {
                backend = Backend.REGISTRY.getOrThrow(Identifier.parse(value));
                useDefaultBackend = false;
                return;
            } catch (IdentifierException _) {
                msg = "'backend' value '" + value + "' is not a valid resource location";
            } catch (IllegalArgumentException _) {
                msg = "Backend with ID '" + value + "' is not registered";
            } catch (Exception _) {
                // Something else went wrong? This should be dead code.
                msg = "'backend' value '" + value + "' is invalid";
            }
        } else if (backendJson != null) {
            msg = "'backend' value must be a string";
        }

        // Don't log an error if the field is missing.
        if (msg != null) {
            FlwImpl.CONFIG_LOGGER.warn(msg);
        }
        backend = BackendManager.offBackend();
        useDefaultBackend = true;
    }

    private void readWorkerThreads(JsonObject object) {
        var value = readInt(object, "workerThreads", WORKER_THREADS_DEFAULT);
        workerThreads = Mth.clamp(value, WORKER_THREADS_MIN, WORKER_THREADS_MAX);
        if (workerThreads != value) {
            FlwImpl.CONFIG_LOGGER.warn("'workerThreads' value of {} is out of range, clamping to {}", value,
                    workerThreads);
        }
    }

    private static boolean readBoolean(JsonObject object, String key, boolean defaultValue) {
        var json = object.get(key);
        if (json instanceof JsonPrimitive primitive && primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (json != null) FlwImpl.CONFIG_LOGGER.warn("'{}' value must be a boolean", key);
        return defaultValue;
    }

    private static int readInt(JsonObject object, String key, int defaultValue) {
        var json = object.get(key);
        if (json instanceof JsonPrimitive primitive && primitive.isNumber()) {
            return primitive.getAsInt();
        }
        if (json != null) FlwImpl.CONFIG_LOGGER.warn("'{}' value must be an integer", key);
        return defaultValue;
    }

    private static <E extends Enum<E> & StringRepresentable> E readEnum(JsonObject object, String key, E defaultValue) {
        var json = object.get(key);
        if (json instanceof JsonPrimitive primitive && primitive.isString()) {
            var value = primitive.getAsString();
            for (var candidate : defaultValue.getDeclaringClass().getEnumConstants()) {
                if (candidate.getSerializedName().equalsIgnoreCase(value)) return candidate;
            }
            FlwImpl.CONFIG_LOGGER.warn("Unknown '{}' value: {}", key, value);
        } else if (json != null) {
            FlwImpl.CONFIG_LOGGER.warn("'{}' value must be a string", key);
        }
        return defaultValue;
    }

    private void readFlwBackends(JsonObject object) {
        var flwBackendsJson = object.get("flw_backends");

        if (flwBackendsJson instanceof JsonObject flwBackendsObject) {
            backendConfig.fromJson(flwBackendsObject);
        } else if (flwBackendsJson != null) {
            FlwImpl.CONFIG_LOGGER.warn("'flw_backends' value must be an object");
        }
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("backend", useDefaultBackend ? DEFAULT_BACKEND_STR : Backend.REGISTRY.getIdOrThrow(backend)
                                                                                                .toString());
        object.addProperty("limitUpdates", limitUpdates);
        object.addProperty("workerThreads", workerThreads);
        object.addProperty("useCommonPool", useCommonPool);
        object.addProperty("concurrentExtraction", concurrentExtraction);
        object.add("flw_backends", backendConfig.toJson());
        return object;
    }

    public static final class FabricBackendConfig implements BackendConfig {
        public static final LightSmoothness LIGHT_SMOOTHNESS_DEFAULT = LightSmoothness.SMOOTH;
        public static final ShaderCompilationMode SHADER_COMPILATION_DEFAULT = ShaderCompilationMode.PRECOMPILE;
        public static final TerrainMode TERRAIN_MODE_DEFAULT = TerrainMode.TRANSLUCENT_OIT;

        public LightSmoothness lightSmoothness = LIGHT_SMOOTHNESS_DEFAULT;
        public ShaderCompilationMode shaderCompilation = SHADER_COMPILATION_DEFAULT;
        public TerrainMode terrainMode = TERRAIN_MODE_DEFAULT;

        private static void readOit(JsonObject object) {
            var oit = object.get("oit") instanceof JsonObject config ? config : new JsonObject();
            var path = readEnum(oit, "path", OitConfig.Path.AUTO);
            var kbuffer = readInt(oit, "kbufferLayers", 0);
            var mlab = readInt(oit, "mlabLayers", 0);
            var abuffer = readInt(oit, "abufferLayers", 0);
            var shaderpack = readEnum(oit, "shaderpack", OitConfig.ShaderpackMode.DEFERRED);
            #if MC < 26.3
            var exactWeather = readBoolean(oit, "exactWeather", false);
            #endif
            OitConfig.loadState(path, kbuffer, mlab, abuffer, shaderpack #if MC < 26.3 , exactWeather #endif);
        }

        @Override
        public LightSmoothness lightSmoothness() {
            return lightSmoothness;
        }

        @Override
        public ShaderCompilationMode shaderCompilation() {
            return shaderCompilation;
        }

        @Override
        public TerrainMode terrainMode() {
            return TerrainModeGate.effective(terrainMode);
        }

        public void fromJson(JsonObject object) {
            lightSmoothness = readEnum(object, "lightSmoothness", LIGHT_SMOOTHNESS_DEFAULT);
            shaderCompilation = readEnum(object, "shader_compilation", SHADER_COMPILATION_DEFAULT);
            terrainMode = readEnum(object, "terrain", TERRAIN_MODE_DEFAULT);
            readOit(object);
        }

        public JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("lightSmoothness", lightSmoothness.getSerializedName());
            object.addProperty("shader_compilation", shaderCompilation.getSerializedName());
            object.addProperty("terrain", terrainMode.getSerializedName());

            JsonObject oit = new JsonObject();
            oit.addProperty("path", OitConfig.path().getSerializedName());
            oit.addProperty("shaderpack", OitConfig.shaderpackMode().getSerializedName());
            oit.addProperty("kbufferLayers", OitConfig.rawLayers(OitConfig.Path.KBUFFER));
            oit.addProperty("mlabLayers", OitConfig.rawLayers(OitConfig.Path.MLAB));
            oit.addProperty("abufferLayers", OitConfig.rawLayers(OitConfig.Path.ABUFFER));
            #if MC < 26.3
            oit.addProperty("exactWeather", OitConfig.exactFabulous());
            #endif
            object.add("oit", oit);
            return object;
        }
    }
}
