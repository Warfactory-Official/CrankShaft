package dev.engine_room.flywheel.impl;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.IdentifierException;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public final class FabricFlwConfig implements FlwConfig {
	public static final Path PATH = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("crankshaft.json");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
			.create();

	public static final boolean LIMIT_UPDATES_DEFAULT = true;
	public static final int WORKER_THREADS_DEFAULT = -1;
	public static final boolean USE_COMMON_POOL_DEFAULT = false;
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

	public final FabricBackendConfig backendConfig = new FabricBackendConfig();

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
	public boolean limitUpdates() {
		return limitUpdates;
	}

	@Override
	public int workerThreadCount() {
		int processors = Runtime.getRuntime()
				.availableProcessors();
		int workers = workerThreads <= 0 ? Math.max(1, processors + workerThreads) : Math.min(workerThreads, processors);
		return Math.max(1, workers);
	}

	@Override
	public boolean useCommonPool() {
		return useCommonPool;
	}

	@Override
	public BackendConfig backendConfig() {
		return backendConfig;
	}

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

	public void setLimitUpdates(boolean value) {
		limitUpdates = value;
		save();
	}

	public void setLightSmoothness(LightSmoothness value) {
		backendConfig.lightSmoothness = value;
		save();
	}

	public void setTerrainMode(TerrainMode value) {
		backendConfig.terrainMode = value;
		save();
	}

	public void setOwnGeometry(boolean value) {
		backendConfig.ownGeometry = value;
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
		try (FileWriter writer = new FileWriter(file)) {
			GSON.toJson(toJson(), writer);
		} catch (Exception e) {
			FlwImpl.CONFIG_LOGGER.warn("Could not save config to file '{}'", file.getAbsolutePath(), e);
		}
	}

	public void fromJson(JsonElement json) {
		if (!(json instanceof JsonObject object)) {
			FlwImpl.CONFIG_LOGGER.warn("Config JSON must be an object");
			backend = BackendManager.offBackend();
			useDefaultBackend = true;
			limitUpdates = LIMIT_UPDATES_DEFAULT;
			workerThreads = WORKER_THREADS_DEFAULT;
			useCommonPool = USE_COMMON_POOL_DEFAULT;
			return;
		}

		readBackend(object);
		readLimitUpdates(object);
		readWorkerThreads(object);
		readUseCommonPool(object);
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
				this.backend = Backend.REGISTRY.getOrThrow(Identifier.parse(value));
				useDefaultBackend = false;
				return;
			} catch (IdentifierException e) {
				msg = "'backend' value '" + value + "' is not a valid resource location";
			} catch (IllegalArgumentException e) {
				msg = "Backend with ID '" + value + "' is not registered";
			} catch (Exception e) {
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

	private void readLimitUpdates(JsonObject object) {
		var limitUpdatesJson = object.get("limitUpdates");

		if (limitUpdatesJson instanceof JsonPrimitive primitive && primitive.isBoolean()) {
			limitUpdates = primitive.getAsBoolean();
			return;
		} else if (limitUpdatesJson != null) {
			FlwImpl.CONFIG_LOGGER.warn("'limitUpdates' value must be a boolean");
		}

		limitUpdates = LIMIT_UPDATES_DEFAULT;
	}

	private void readWorkerThreads(JsonObject object) {
		var workerThreadsJson = object.get("workerThreads");

		if (workerThreadsJson instanceof JsonPrimitive primitive && primitive.isNumber()) {
			int value = primitive.getAsInt();
			int clamped = Mth.clamp(value, WORKER_THREADS_MIN, WORKER_THREADS_MAX);

			if (clamped != value) {
				FlwImpl.CONFIG_LOGGER.warn("'workerThreads' value of {} is out of range, clamping to {}", value, clamped);
			}

			workerThreads = clamped;
			return;
		} else if (workerThreadsJson != null) {
			FlwImpl.CONFIG_LOGGER.warn("'workerThreads' value must be an integer");
		}

		workerThreads = WORKER_THREADS_DEFAULT;
	}

	private void readUseCommonPool(JsonObject object) {
		var useCommonPoolJson = object.get("useCommonPool");

		if (useCommonPoolJson instanceof JsonPrimitive primitive && primitive.isBoolean()) {
			useCommonPool = primitive.getAsBoolean();
			return;
		} else if (useCommonPoolJson != null) {
			FlwImpl.CONFIG_LOGGER.warn("'useCommonPool' value must be a boolean");
		}

		useCommonPool = USE_COMMON_POOL_DEFAULT;
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
		object.add("flw_backends", backendConfig.toJson());
		return object;
	}

	public static final class FabricBackendConfig implements BackendConfig {
		public static final LightSmoothness LIGHT_SMOOTHNESS_DEFAULT = LightSmoothness.SMOOTH;
		public static final TerrainMode TERRAIN_MODE_DEFAULT = TerrainMode.OFF;

        public LightSmoothness lightSmoothness = LIGHT_SMOOTHNESS_DEFAULT;
		public TerrainMode terrainMode = TERRAIN_MODE_DEFAULT;
		public boolean ownGeometry = false;

		@Override
		public LightSmoothness lightSmoothness() {
			return lightSmoothness;
		}

		@Override
		public TerrainMode terrainMode() {
			return TerrainModeGate.effective(terrainMode);
		}

		@Override
		public boolean ownGeometry() {
			return ownGeometry;
		}

		public void fromJson(JsonObject object) {
			readLightSmoothness(object);
			terrainMode = readTerrainMode(object);
			readOwnGeometry(object);
			readOit(object);
		}

		private void readOwnGeometry(JsonObject object) {
			var json = object.get("ownGeometry");
			if (json instanceof JsonPrimitive primitive && primitive.isBoolean()) {
				ownGeometry = primitive.getAsBoolean();
				return;
			} else if (json != null) {
				FlwBackend.LOGGER.warn("'ownGeometry' value must be a boolean");
			}
			ownGeometry = false;
		}

		private static void readOit(JsonObject object) {
			OitConfig.Path path = OitConfig.Path.AUTO;
			int kbuffer = 0;
			int mlab = 0;
			int abuffer = 0;
			boolean exactWeather = false;
			if (object.get("oit") instanceof JsonObject oit) {
				if (oit.get("path") instanceof JsonPrimitive p && p.isString()) {
					for (OitConfig.Path v : OitConfig.Path.values()) {
						if (v.name().equalsIgnoreCase(p.getAsString())) {
							path = v;
							break;
						}
					}
				}
				kbuffer = readInt(oit, "kbufferLayers");
				mlab = readInt(oit, "mlabLayers");
				abuffer = readInt(oit, "abufferLayers");
				exactWeather = oit.get("exactWeather") instanceof JsonPrimitive e && e.isBoolean() && e.getAsBoolean();
			}
			OitConfig.loadState(path, kbuffer, mlab, abuffer, exactWeather);
		}

		private static int readInt(JsonObject object, String key) {
			return object.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : 0;
		}

		private static TerrainMode readTerrainMode(JsonObject object) {
			var json = object.get("terrain");
			if (json instanceof JsonPrimitive primitive && primitive.isString()) {
				TerrainMode mode = TerrainMode.byToken(primitive.getAsString());
				if (mode != null) {
					return mode;
				}
				FlwBackend.LOGGER.warn("Unknown 'terrain' value: {}", primitive.getAsString());
			} else if (json != null) {
				FlwBackend.LOGGER.warn("'terrain' value must be a string");
			}
			return TERRAIN_MODE_DEFAULT;
		}

		private void readLightSmoothness(JsonObject object) {
			var lightSmoothnessJson = object.get("lightSmoothness");
			String msg = null;

			if (lightSmoothnessJson instanceof JsonPrimitive primitive && primitive.isString()) {
				var value = primitive.getAsString();

				for (var item : LightSmoothness.values()) {
					if (item.name()
							.equalsIgnoreCase(value)) {
						lightSmoothness = item;
						return;
					}
				}

				msg = "Unknown 'lightSmoothness' value: " + value;
			} else if (lightSmoothnessJson != null) {
				msg = "'lightSmoothness' value must be a string";
			}

			// Don't log an error if the field is missing.
			if (msg != null) {
				FlwBackend.LOGGER.warn(msg);
			}
			lightSmoothness = LIGHT_SMOOTHNESS_DEFAULT;
		}

		public JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("lightSmoothness", lightSmoothness.getSerializedName());
			object.addProperty("terrain", terrainMode.token());
			object.addProperty("ownGeometry", ownGeometry);

			JsonObject oit = new JsonObject();
			oit.addProperty("path", OitConfig.path().name().toLowerCase(java.util.Locale.ROOT));
			oit.addProperty("kbufferLayers", OitConfig.rawLayers(OitConfig.Path.KBUFFER));
			oit.addProperty("mlabLayers", OitConfig.rawLayers(OitConfig.Path.MLAB));
			oit.addProperty("abufferLayers", OitConfig.rawLayers(OitConfig.Path.ABUFFER));
			oit.addProperty("exactWeather", OitConfig.exactFabulous());
			object.add("oit", oit);
			return object;
		}
	}
}
