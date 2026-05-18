package dev.engine_room.flywheel.api.visualization;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jspecify.annotations.Nullable;

/**
 * Redirects crumbling-overlay positions for multi-block TEs whose visual lives at a "core"
 * position other than the block the player is hitting. No-op if no resolver is registered.
 */
public final class CrumblingPosRedirector {
    @FunctionalInterface
    public interface Resolver {
        /** Return the core position for the given dummy position, or {@code null} if unchanged. */
        @Nullable BlockPos resolve(World world, BlockPos pos);
    }

    private static Resolver RESOLVER = (w, p) -> null;

    private CrumblingPosRedirector() {
    }

    public static void register(Resolver resolver) {
        RESOLVER = resolver != null ? resolver : (w, p) -> null;
    }

    @Nullable
    public static BlockPos resolve(World world, BlockPos pos) {
        return RESOLVER.resolve(world, pos);
    }
}
