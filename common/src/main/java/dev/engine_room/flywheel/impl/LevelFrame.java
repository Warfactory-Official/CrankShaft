package dev.engine_room.flywheel.impl;

import net.minecraft.client.renderer.SubmitNodeStorage;
import org.jspecify.annotations.Nullable;

/** Submit storage of the frame {@code LevelRenderer.render} prepares; hand, GUI and PiP frames use others. */
public final class LevelFrame {
    @Nullable
    private static SubmitNodeStorage submits;

    private LevelFrame() {
    }

    public static void submits(SubmitNodeStorage storage) {
        submits = storage;
    }

    public static boolean owns(@Nullable SubmitNodeStorage storage) {
        return storage != null && storage == submits;
    }
}
