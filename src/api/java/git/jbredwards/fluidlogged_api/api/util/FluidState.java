package git.jbredwards.fluidlogged_api.api.util;

import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.Fluid;

public class FluidState {
    public static FluidState get(IBlockAccess world, BlockPos pos) {
        throw new AssertionError();
    }

    public boolean isEmpty() {
        throw new AssertionError();
    }

    public Fluid getFluid() {
        throw new AssertionError();
    }

    public Material getMaterial() {
        throw new AssertionError();
    }

    public float getActualHeight(IBlockAccess world, BlockPos pos) {
        throw new AssertionError();
    }
}
