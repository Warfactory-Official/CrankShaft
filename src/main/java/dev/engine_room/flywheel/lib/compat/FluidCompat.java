package dev.engine_room.flywheel.lib.compat;

import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fml.common.Loader;

/**
 * Probes the fluid at a world position for the {@code cameraInFluid} / {@code eyeInFluid} uniform
 * slots. Returned int matches upstream Flywheel: {@code 0 = none}, {@code 1 = water},
 * {@code 2 = lava}, {@code -1 = custom fluid}.
 */
public interface FluidCompat {
    FluidCompat INSTANCE = pick();

    int probeFluid(IBlockAccess level, BlockPos blockPos, Vec3d pos);

    @SuppressWarnings("UnreachableCode")
    private static FluidCompat pick() {
        if (Loader.isModLoaded("fluidlogged_api")) {
            return (level, blockPos, pos) -> {
                FluidState fs = FluidState.get(level, blockPos);
                if (fs.isEmpty()) {
                    return 0;
                }
                if (pos.y >= blockPos.getY() + fs.getActualHeight(level, blockPos)) {
                    return 0;
                }
                Fluid fluid = fs.getFluid();
                if (fluid != null && fluid.isGaseous()) {
                    return -1;
                }
                Material material = fs.getMaterial();
                if (material == Material.WATER) return 1;
                if (material == Material.LAVA) return 2;
                return -1;
            };
        }
        return (level, blockPos, pos) -> {
            IBlockState state = level.getBlockState(blockPos);
            Material material = state.getMaterial();
            if (material != Material.WATER && material != Material.LAVA) {
                return 0;
            }
            float height = BlockLiquid.getBlockLiquidHeight(state, level, blockPos);
            if (pos.y >= blockPos.getY() + height) {
                return 0;
            }
            return material == Material.WATER ? 1 : 2;
        };
    }
}
