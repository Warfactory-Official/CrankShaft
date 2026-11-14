package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;

/**
 * Loader configuration supplied by {@link FlwImplXplat#getConfig()}. Client settings bindings call the setters
 * on the client thread and persist immediately outside a settings Apply batch. The UI handles renderer refresh
 * and restart requirements.
 */
public interface FlwConfig {
    String DEFAULT_BACKEND_STR = "DEFAULT";

    FlwConfig INSTANCE = FlwImplXplat.INSTANCE.getConfig();

    Backend backend();

    /**
     * The saved preference, including DEFAULT rather than its resolved backend.
     */
    String backendString();

    void setBackendString(String value);

    boolean limitUpdates();

    void setLimitUpdates(boolean value);

    /**
     * The configured absolute or relative count, before resolving it against the available processors.
     */
    int workerThreads();

    void setWorkerThreads(int value);

    int workerThreadCount();

    boolean useCommonPool();

    void setUseCommonPool(boolean value);

    boolean concurrentExtraction();

    void setConcurrentExtraction(boolean value);

    BackendConfig backendConfig();

    void setLightSmoothness(LightSmoothness value);

    void setTerrainMode(TerrainMode value);

    /**
     * Defers writes while Sodium applies bindings on the client thread.
     */
    void beginBatch();

    /**
     * Storage flush persists once; an Apply finally block only releases deferral after a failed binding/flush.
     */
    void endBatch(boolean persist);
}
