package dev.engine_room.flywheel.lib.util;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class RendererReloadCache<T, U> implements Function<T, U> {
    private static final Set<RendererReloadCache<?, ?>> ALL = Collections.newSetFromMap(new WeakHashMap<>());
    private final Function<T, U> factory;
    private final Map<T, U> map = new ConcurrentHashMap<>();

    public RendererReloadCache(Function<T, U> factory) {
        this.factory = factory;
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    public U get(T key) {
        return map.computeIfAbsent(key, factory);
    }

    @Override
    public U apply(T t) {
        return get(t);
    }

    public void clear() {
        map.clear();
    }

    // CrankShaft: reload generation, bumped alongside the cache clears. Render-thread only.
    // Lets callers memoize a reload-dependent scalar against an int compare instead of paying
    // a single-entry cache's map lookup per query.
    private static int reloadCount = 0;

    public static int reloadCount() {
        return reloadCount;
    }

    @ApiStatus.Internal
    public static void onReloadLevelRenderer() {
        reloadCount++;
        for (RendererReloadCache<?, ?> cache : ALL) {
            cache.clear();
        }
    }
}
