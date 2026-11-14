package dev.engine_room.flywheel.api.backend;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.function.LongSupplier;

/**
 * The millisecond clock instance vertex shaders read as {@code uint flw_gameMillis}: its low 32 bits, sampled once
 * per frame on the render thread before any pass. Without an installed clock it reads the level's game time times 50
 * plus {@code (long) (partialTick * 50F)}. The difference of two samples, taken as {@code int}, is exact while they
 * lie within 2^31 ms of each other. Cull shaders do not see it.
 */
public final class GameClock {
    private static @Nullable LongSupplier clock;

    private GameClock() {
    }

    /**
     * During client setup, before the first frame, at most once.
     */
    public static void install(LongSupplier millis) {
        if (clock != null) {
            throw new IllegalStateException("A game clock is already installed");
        }
        clock = millis;
    }

    @ApiStatus.Internal
    public static @Nullable LongSupplier installed() {
        return clock;
    }
}
