package dev.engine_room.flywheel.api.visualization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-mod registry routing a block under destruction to the block entity whose visual draws it, for structures
 * whose blocks span positions without a visual of their own.
 *
 * <p>Register during client initialization, before the first level render. Resolvers run on the render thread,
 * only for a destroyed position without a visual; the first non-null answer wins. Every position resolving to one
 * visual cracks it once, at the highest progress.
 */
public final class CrumblingOwners {
    private static final List<Resolver> RESOLVERS = new ArrayList<>();

    private CrumblingOwners() {
    }

    public static void register(Resolver resolver) {
        RESOLVERS.add(resolver);
    }

    public static @Nullable BlockPos ownerOf(BlockGetter level, BlockPos pos) {
        if (RESOLVERS.isEmpty()) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        for (Resolver resolver : RESOLVERS) {
            BlockPos owner = resolver.ownerOf(level, pos, state);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface Resolver {
        /**
         * @return the position of the block entity whose visual draws {@code pos}, or {@code null} to defer
         */
        @Nullable BlockPos ownerOf(BlockGetter level, BlockPos pos, BlockState state);
    }
}
