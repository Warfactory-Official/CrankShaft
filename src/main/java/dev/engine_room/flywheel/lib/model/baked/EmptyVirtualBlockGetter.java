package dev.engine_room.flywheel.lib.model.baked;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.function.ToIntFunction;

public class EmptyVirtualBlockGetter extends VirtualBlockGetter {
    public static final EmptyVirtualBlockGetter FULL_DARK = new EmptyVirtualBlockGetter(p -> 0, p -> 0);
    public static final EmptyVirtualBlockGetter FULL_BRIGHT = new EmptyVirtualBlockGetter(p -> 15, p -> 15);

    public EmptyVirtualBlockGetter(ToIntFunction<BlockPos> blockLightFunc, ToIntFunction<BlockPos> skyLightFunc) {
        super(blockLightFunc, skyLightFunc);
    }

    @Override
    @Nullable
    public final TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    @Override
    public final IBlockState getBlockState(BlockPos pos) {
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public final boolean isAirBlock(BlockPos pos) {
        return true;
    }
}
