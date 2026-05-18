package dev.engine_room.flywheel.lib.model.baked;

import net.minecraft.init.Biomes;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

import java.util.function.ToIntFunction;

/**
 * 1.12.2: light values are produced by two {@link ToIntFunction}s and packed into
 * {@code getCombinedLight}'s {@code (skyLight << 20 | blockLight << 4)} layout so callers can
 * feed {@code BlockModelRenderer}'s smooth-lighting path directly.
 */
public abstract class VirtualBlockGetter implements IBlockAccess {
    protected final ToIntFunction<BlockPos> blockLightFunc;
    protected final ToIntFunction<BlockPos> skyLightFunc;

    public VirtualBlockGetter(ToIntFunction<BlockPos> blockLightFunc, ToIntFunction<BlockPos> skyLightFunc) {
        this.blockLightFunc = blockLightFunc;
        this.skyLightFunc = skyLightFunc;
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        int block = Math.max(blockLightFunc.applyAsInt(pos), lightValue);
        int sky = skyLightFunc.applyAsInt(pos);
        return (sky << 20) | (block << 4);
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return getBlockState(pos).getBlock().isAir(getBlockState(pos), this, pos);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return Biomes.PLAINS;
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return 0;
    }

    @Override
    public WorldType getWorldType() {
        return WorldType.DEFAULT;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return getBlockState(pos).isSideSolid(this, pos, side);
    }
}
