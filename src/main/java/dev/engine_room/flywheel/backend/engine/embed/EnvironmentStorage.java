package dev.engine_room.flywheel.backend.engine.embed;

import dev.engine_room.flywheel.backend.engine.CpuArena;

import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;

public class EnvironmentStorage {
    public static final int MATRIX_SIZE_BYTES = (16 + 12) * Float.BYTES;

    protected final Object lock = new Object();

    protected final ReferenceSet<EmbeddedEnvironment> environments = new ReferenceLinkedOpenHashSet<>();

    public final CpuArena arena = new CpuArena(MATRIX_SIZE_BYTES, 32);

    {
        // Reserve the identity matrix at index 0.
        arena.alloc();
    }

    public void track(EmbeddedEnvironment environment) {
        synchronized (lock) {
            if (environments.add(environment)) {
                environment.matrixIndex = arena.alloc();
            }
        }
    }

    public void flush() {
        environments.removeIf(embeddedEnvironment -> {
            var deleted = embeddedEnvironment.isDeleted();
            if (deleted && embeddedEnvironment.matrixIndex > 0) {
                arena.free(embeddedEnvironment.matrixIndex);
            }
            return deleted;
        });
        for (EmbeddedEnvironment environment : environments) {
            environment.flush(arena.indexToPointer(environment.matrixIndex));
        }
    }

    public void delete() {
        arena.delete();
    }
}
