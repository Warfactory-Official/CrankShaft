package dev.engine_room.flywheel.lib.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.minecraft.world.World;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.function.Function;

public final class LevelAttached<T> {
    private static final ConcurrentLinkedDeque<WeakReference<LevelAttached<?>>> ALL = new ConcurrentLinkedDeque<>();
    private static final Cleaner CLEANER = Cleaner.create();

    private final LoadingCache<World, T> cache;

    public LevelAttached(Function<World, T> factory, Consumer<T> finalizer) {
        WeakReference<LevelAttached<?>> thisRef = new WeakReference<>(this);
        ALL.add(thisRef);

        cache = CacheBuilder.newBuilder()
                .<World, T>removalListener(n -> finalizer.accept(n.getValue()))
                .build(new CacheLoader<>() {
                    @Override
                    public T load(World key) {
                        return factory.apply(key);
                    }
                });

        CLEANER.register(this, new CleaningAction(thisRef, cache));
    }

    public LevelAttached(Function<World, T> factory) {
        this(factory, t -> {});
    }

    public static void invalidateLevel(World level) {
        Iterator<WeakReference<LevelAttached<?>>> iterator = ALL.iterator();
        while (iterator.hasNext()) {
            LevelAttached<?> attached = iterator.next().get();
            if (attached == null) {
                iterator.remove();
            } else {
                attached.remove(level);
            }
        }
    }

    public T get(World level) {
        return cache.getUnchecked(level);
    }

    public void remove(World level) {
        cache.invalidate(level);
    }

    public T refresh(World level) {
        remove(level);
        return get(level);
    }

    public void reset() {
        cache.invalidateAll();
    }

    private static class CleaningAction implements Runnable {
        private final WeakReference<LevelAttached<?>> ref;
        private final LoadingCache<World, ?> cache;

        private CleaningAction(WeakReference<LevelAttached<?>> ref, LoadingCache<World, ?> cache) {
            this.ref = ref;
            this.cache = cache;
        }

        @Override
        public void run() {
            ALL.remove(ref);
            cache.invalidateAll();
        }
    }
}
