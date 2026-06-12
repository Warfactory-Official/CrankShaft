package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.stream.Stream;

/**
 * An interface that implementors of {@link Instance} should also implement if they wish to make use of
 * {@link #relight} and the relighting utilities in {@link AbstractBlockEntityVisual} and {@link AbstractEntityVisual}.
 */
public interface FlatLit extends Instance {
    static void relight(int packedLight, @Nullable FlatLit... instances) {
        for (FlatLit instance : instances) {
            if (instance != null) {
                instance.light(packedLight)
                        .handle()
                        .setChanged();
            }
        }
    }

    static void relight(int packedLight, Iterator<@Nullable FlatLit> instances) {
        while (instances.hasNext()) {
            FlatLit instance = instances.next();

            if (instance != null) {
                instance.light(packedLight)
                        .handle()
                        .setChanged();
            }
        }
    }

    static void relight(int packedLight, Iterable<@Nullable FlatLit> instances) {
        relight(packedLight, instances.iterator());
    }

    static void relight(int packedLight, Stream<@Nullable FlatLit> instances) {
        relight(packedLight, instances.iterator());
    }

    /**
     * {@code packedLight} is the {@code (skyLight << 20) | (blockLight << 4)} format emitted
     * by {@code World.getCombinedLight} -- pass that through verbatim, do not split bytes.
     */
    FlatLit light(int packedLight);
}
