package dev.engine_room.flywheel.lib.util;

import dev.engine_room.flywheel.lib.compat.DynamicLightProvider;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class LevelRenderer {

    private LevelRenderer() {
    }

    public static int getLightColor(World level, BlockPos pos) {
        return getLightColor(level, level.getBlockState(pos), pos);
    }

    public static int getLightColor(World level, IBlockState state, BlockPos pos) {
        return DynamicLightProvider.INSTANCE.applyLightAt(pos, level.getCombinedLight(pos, state.getLightValue(level, pos)));
    }

    public static int getEntityLight(Entity entity, BlockPos samplePos) {
        return DynamicLightProvider.INSTANCE.getLightForEntity(entity, samplePos);
    }
}
