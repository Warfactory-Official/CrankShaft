package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.Instance;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.stream.Stream;

public interface FlatLit extends Instance {
    /** {@code packedLight} is the {@code (skyLight << 20) | (blockLight << 4)} format emitted
     *  by {@code World.getCombinedLight} — pass that through verbatim, do not split bytes. */
    FlatLit light(int packedLight);

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
}
