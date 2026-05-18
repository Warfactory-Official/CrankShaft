package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.lib.compat.FluidCompat;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

class UniformWriter {
    static long writeInt(long ptr, int value) {
        MemoryUtil.memPutInt(ptr, value);
        return ptr + 4;
    }

    static long writeFloat(long ptr, float value) {
        MemoryUtil.memPutFloat(ptr, value);
        return ptr + 4;
    }

    static long writeVec2(long ptr, float x, float y) {
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        return ptr + 8;
    }

    static long writeVec3(long ptr, float x, float y, float z) {
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
        MemoryUtil.memPutFloat(ptr + 12, 0f);
        return ptr + 16;
    }

    static long writeVec3(long ptr, Vector3fc vec) {
        return writeVec3(ptr, vec.x(), vec.y(), vec.z());
    }

    static long writeVec4(long ptr, float x, float y, float z, float w) {
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
        MemoryUtil.memPutFloat(ptr + 12, w);
        return ptr + 16;
    }

    static long writeIVec2(long ptr, int x, int y) {
        MemoryUtil.memPutInt(ptr, x);
        MemoryUtil.memPutInt(ptr + 4, y);
        return ptr + 8;
    }

    static long writeIVec3(long ptr, int x, int y, int z) {
        MemoryUtil.memPutInt(ptr, x);
        MemoryUtil.memPutInt(ptr + 4, y);
        MemoryUtil.memPutInt(ptr + 8, z);
        MemoryUtil.memPutInt(ptr + 12, 0);
        return ptr + 16;
    }

    static long writeIVec4(long ptr, int x, int y, int z, int w) {
        MemoryUtil.memPutInt(ptr, x);
        MemoryUtil.memPutInt(ptr + 4, y);
        MemoryUtil.memPutInt(ptr + 8, z);
        MemoryUtil.memPutInt(ptr + 12, w);
        return ptr + 16;
    }

    static long writeMat4(long ptr, Matrix4f mat) {
        ExtraMemoryOps.putMatrix4f(ptr, mat);
        return ptr + 64;
    }

    // 1.12.2: no vanilla FluidState — FluidCompat probes via Fluidlogged-API's FluidState.get
    // when present, BlockLiquid otherwise. Slot values match upstream.
    static long writeInFluidAndBlock(long ptr, World level, BlockPos blockPos, Vec3d pos) {
        MemoryUtil.memPutInt(ptr, FluidCompat.INSTANCE.probeFluid(level, blockPos, pos));

        IBlockState state = level.getBlockState(blockPos);
        MemoryUtil.memPutInt(ptr + 4, state.getBlock().isAir(state, level, blockPos) ? 0 : -1);

        return ptr + 8;
    }
}
