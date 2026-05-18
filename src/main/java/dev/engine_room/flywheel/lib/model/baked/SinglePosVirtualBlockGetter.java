package dev.engine_room.flywheel.lib.model.baked;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.function.ToIntFunction;

public class SinglePosVirtualBlockGetter extends VirtualBlockGetter {
    protected BlockPos pos = BlockPos.ORIGIN;
    protected IBlockState blockState = Blocks.AIR.getDefaultState();
    @Nullable
    protected TileEntity tileEntity;

    public SinglePosVirtualBlockGetter(ToIntFunction<BlockPos> blockLightFunc, ToIntFunction<BlockPos> skyLightFunc) {
        super(blockLightFunc, skyLightFunc);
    }

    public static SinglePosVirtualBlockGetter createFullDark() {
        return new SinglePosVirtualBlockGetter(p -> 0, p -> 0);
    }

    public static SinglePosVirtualBlockGetter createFullBright() {
        return new SinglePosVirtualBlockGetter(p -> 15, p -> 15);
    }

    public SinglePosVirtualBlockGetter pos(BlockPos pos) {
        this.pos = pos;
        return this;
    }

    public SinglePosVirtualBlockGetter blockState(IBlockState state) {
        this.blockState = state;
        return this;
    }

    public SinglePosVirtualBlockGetter tileEntity(@Nullable TileEntity tileEntity) {
        this.tileEntity = tileEntity;
        return this;
    }

    @Override
    @Nullable
    public TileEntity getTileEntity(BlockPos pos) {
        return pos.equals(this.pos) ? tileEntity : null;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return pos.equals(this.pos) ? blockState : Blocks.AIR.getDefaultState();
    }
}
