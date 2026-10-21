package dev.engine_room.vanillin.oit;

import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStainedGlassPane;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.jspecify.annotations.Nullable;

final class OitDemoBlockAccess implements IBlockAccess {
    private final IBlockAccess level;

    OitDemoBlockAccess(IBlockAccess level) {
        this.level = level;
    }

    static IBlockState renderedState(IBlockState state) {
        return switch (state.getBlock()) {
            case BlockOitDemoStainedGlass _ -> Blocks.STAINED_GLASS.getDefaultState()
                                                                   .withProperty(BlockStainedGlass.COLOR,
                                                                           state.getValue(BlockOitDemoStained.COLOR));
            case BlockOitDemoStainedGlassPane _ -> Blocks.STAINED_GLASS_PANE.getDefaultState()
                                                                            .withProperty(BlockStainedGlassPane.COLOR,
                                                                                    state.getValue(
                                                                                            BlockOitDemoStained.COLOR));
            case BlockOitDemoGlassPane _ -> Blocks.GLASS_PANE.getDefaultState();
            case BlockOitDemoGlass _ -> Blocks.GLASS.getDefaultState();
            default -> state;
        };
    }

    int cullMask(IBlockState state, BlockPos pos) {
        var mask = 0;
        for (var direction : EnumFacing.VALUES) {
            if (!state.shouldSideBeRendered(this, pos, direction)) mask |= 1 << direction.ordinal();
        }
        return mask;
    }

    // Both visual and TESR must compare neighboring markers as their vanilla glass states.
    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return renderedState(level.getBlockState(pos));
    }

    @Override
    public @Nullable TileEntity getTileEntity(BlockPos pos) {
        return level.getTileEntity(pos);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        return level.getCombinedLight(pos, lightValue);
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return level.isAirBlock(pos);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return level.getBiome(pos);
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return level.getStrongPower(pos, direction);
    }

    @Override
    public WorldType getWorldType() {
        // 1.12.2: the dispatcher's debug-world path accepts resolved states without recalculating pane connections.
        return WorldType.DEBUG_ALL_BLOCK_STATES;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean defaultValue) {
        return getBlockState(pos).isSideSolid(this, pos, side);
    }
}
