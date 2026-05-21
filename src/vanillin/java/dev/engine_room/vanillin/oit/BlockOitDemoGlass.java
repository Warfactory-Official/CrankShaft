package dev.engine_room.vanillin.oit;

import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.world.World;
import org.jspecify.annotations.Nullable;

/**
 * Mirrors {@code minecraft:glass} — marker for a Flywheel-rendered plain glass cube. Invisible
 * to the chunk renderer; the {@link OitDemoVisual} bound to the TE draws the cube model at the
 * marker's own position so the user can place a real vanilla {@code glass} block next to it for
 * direct A/B comparison.
 */
public class BlockOitDemoGlass extends Block implements ITileEntityProvider {
    public BlockOitDemoGlass() {
        super(Material.IRON);
    }

    @Override
    public @Nullable TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileEntityOitDemo();
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }
}
