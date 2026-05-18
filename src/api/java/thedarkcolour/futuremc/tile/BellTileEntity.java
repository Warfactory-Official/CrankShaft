package thedarkcolour.futuremc.tile;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;

public abstract class BellTileEntity extends TileEntity {
    public abstract int getRingingTicks();

    public abstract boolean isRinging();

    public abstract EnumFacing getRingFacing();
}
