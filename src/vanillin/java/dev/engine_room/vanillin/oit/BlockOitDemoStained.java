package dev.engine_room.vanillin.oit;

import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

/**
 * Shared base for the 16-meta stained-glass demo markers. The two concrete subclasses
 * ({@link BlockOitDemoStainedGlass} cube / {@link BlockOitDemoStainedGlassPane} pane) stay
 * distinct classes — never a subtype of each other — because {@link OitDemoVisual} and
 * {@link OitDemoRenderer} dispatch on the concrete type via {@code instanceof} (cube checked
 * before pane), so collapsing them would route panes to the cube model.
 */
public abstract class BlockOitDemoStained extends BlockOitDemoGlass {
    public static final PropertyEnum<EnumDyeColor> COLOR = PropertyEnum.create("color", EnumDyeColor.class);

    protected BlockOitDemoStained() {
        setDefaultState(blockState.getBaseState().withProperty(COLOR, EnumDyeColor.WHITE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, COLOR);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(COLOR, EnumDyeColor.byMetadata(meta & 15));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(COLOR).getMetadata();
    }

    @Override
    public int damageDropped(IBlockState state) {
        return state.getValue(COLOR).getMetadata();
    }

    @Override
    public void getSubBlocks(CreativeTabs tab, NonNullList<ItemStack> items) {
        for (EnumDyeColor color : EnumDyeColor.values()) {
            items.add(new ItemStack(this, 1, color.getMetadata()));
        }
    }
}
