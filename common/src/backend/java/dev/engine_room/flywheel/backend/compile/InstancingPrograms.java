package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.util.AtomicReferenceCounted;
import org.jspecify.annotations.Nullable;

public class InstancingPrograms extends AtomicReferenceCounted {
    @Nullable
    private static InstancingPrograms instance;

    private InstancingPrograms() {
    }

    static void reload() {
        if (!GlCompat.SUPPORTS_INSTANCING) {
            return;
        }
        setInstance(new InstancingPrograms());
    }

    static void setInstance(@Nullable InstancingPrograms newInstance) {
        if (instance != null) {
            instance.release();
        }
        if (newInstance != null) {
            newInstance.acquire();
        }
        instance = newInstance;
    }

    @Nullable
    public static InstancingPrograms get() {
        return instance;
    }

    public static boolean allLoaded() {
        return instance != null;
    }

    public static void kill() {
        setInstance(null);
    }

    @Override
    protected void _delete() {
    }
}
