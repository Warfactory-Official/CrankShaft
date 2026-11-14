package dev.engine_room.flywheel.api.backend;

import dev.engine_room.flywheel.api.internal.FlwApiLink;
import dev.engine_room.flywheel.api.registry.IdRegistry;
import net.minecraft.world.level.LevelAccessor;

@BackendImplemented
public interface Backend {
    IdRegistry<Backend> REGISTRY = FlwApiLink.INSTANCE.createIdRegistry();

    /**
     * Create a new engine instance.
     */
    Engine createEngine(LevelAccessor level);

    /**
     * The priority of this backend.
     * <p>The automatically selectable backend with the highest priority upon first launch is the default backend.
     *
     * <p>If the selected backend becomes unavailable, fallback first tries lower-priority automatic backends,
     * then retries the remaining supported automatic backends before settling on the off backend.
     *
     * @return The priority of this backend.
     */
    int priority();

    /**
     * Check if this backend is supported.
     */
    boolean isSupported();

    /**
     * Whether default and fallback selection may choose this backend. Explicit config or command selection
     * still uses {@link #isSupported()}; manual backends remain available in the backend selector.
     */
    default boolean automaticSelection() {
        return true;
    }

    /**
     * Whether this backend is GPU-driven: its draw is compute-culled and GPU-built (terrain takeover,
     * GPU MDI / mesh-shader draw). Non-GPU-driven backends (e.g. instancing) return {@code false}.
     */
    default boolean isGpuDriven() {
        return false;
    }
}
