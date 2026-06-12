package dev.engine_room.flywheel.lib.util;

import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Flywheel's stand-in for vanilla's removed {@code LevelRenderer.getLightColor}.
 */
public final class LevelRenderer {
    private LevelRenderer() {
    }

    public static int getLightColor(Level level, BlockPos pos) {
        return getLightColor(level, level.getBlockState(pos), pos);
    }

    public static int getLightColor(Level level, BlockState state, BlockPos pos) {
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int block = Math.max(level.getBrightness(LightLayer.BLOCK, pos), state.getLightEmission());
        return LightCoordsUtil.pack(block, sky);
    }
}
