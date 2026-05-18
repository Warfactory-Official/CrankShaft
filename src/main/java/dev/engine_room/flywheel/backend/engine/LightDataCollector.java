package dev.engine_room.flywheel.backend.engine;

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.Loader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Shared light/solid-bit packing for an 18x18x18 section neighborhood. The vanilla 9-column +
 * 27-array-index storage fetch is in {@link VanillaLightDataCollector}; the CubicChunks 27-cube
 * fetch is in {@link CubicLightDataCollector}, only loaded when CC is installed.
 */
public abstract class LightDataCollector {
    private static final boolean CUBICCHUNKS_LOADED = Loader.isModLoaded("cubicchunks");
    static final int SOLID_LONGS = (LightStorage.BLOCKS_PER_SECTION + 63) >>> 6;

    final int defaultSkyLight;
    final ExtendedBlockStorage[] storages = new ExtendedBlockStorage[27];
    final long[] solid = new long[SOLID_LONGS];

    LightDataCollector(World level) {
        this.defaultSkyLight = level.provider.hasSkyLight() ? 15 : 0;
    }

    public static LightDataCollector of(World level) {
        if (CUBICCHUNKS_LOADED) {
            LightDataCollector cubic = tryCubic(level);
            if (cubic != null) return cubic;
        }
        return new VanillaLightDataCollector(level);
    }

    @Nullable
    private static LightDataCollector tryCubic(World level) {
        if (level instanceof ICubicWorld cw && cw.isCubicWorld()) {
            return new CubicLightDataCollector(level, cw.getCubeCache());
        }
        return null;
    }

    public final void collectSection(long ptr, long section) {
        final int sx = SectionPos.x(section);
        final int sy = SectionPos.y(section);
        final int sz = SectionPos.z(section);

        for (int i = 0; i < 27; i++) storages[i] = null;
        fillStorages(sx, sy, sz);

        final int xBlockMin = sx << 4;
        final int yBlockMin = sy << 4;
        final int zBlockMin = sz << 4;
        final int defaultSky = defaultSkyLight;

        for (int i = 0; i < SOLID_LONGS; i++) solid[i] = 0L;

        final long lightPtr = ptr + LightStorage.SOLID_SIZE_BYTES;

        int idx = 0;
        for (int y = -1; y < 17; y++) {
            final int gy = yBlockMin + y;
            final int yi = (gy >> 4) - (sy - 1);
            final int ly = gy & 0xF;
            final int y1 = y + 1;
            for (int z = -1; z < 17; z++) {
                final int gz = zBlockMin + z;
                final int zi = (gz >> 4) - (sz - 1);
                final int lz = gz & 0xF;
                final int z1 = z + 1;
                for (int x = -1; x < 17; x++) {
                    final int gx = xBlockMin + x;
                    final int xi = (gx >> 4) - (sx - 1);
                    final int lx = gx & 0xF;

                    ExtendedBlockStorage s = storages[xi + zi * 3 + yi * 9];
                    int block;
                    int sky;
                    if (s == null) {
                        block = 0;
                        sky = defaultSky;
                    } else {
                        IBlockState bs = s.get(lx, ly, lz);
                        if (bs.isOpaqueCube() && bs.isFullCube()) {
                            solid[idx >>> 6] |= 1L << (idx & 63);
                        }
                        block = s.getBlockLight().get(lx, ly, lz);
                        NibbleArray skyArr = s.getSkyLight();
                        sky = skyArr != null ? skyArr.get(lx, ly, lz) : defaultSky;
                    }

                    int offset = (x + 1) + z1 * 18 + y1 * 18 * 18;
                    MemoryUtil.memPutByte(lightPtr + offset, (byte) ((block & 0xF) | ((sky & 0xF) << 4)));
                    idx++;
                }
            }
        }

        // SOLID_SIZE_BYTES = ceil(5832/32)*4 = 732, which is 91 full longs + 4 bytes.
        // Writing the 92nd long whole would overflow into the first 4 light bytes
        // (already written above) and zero them out — upstream sidesteps this by running
        // collectSolidData before collectLightData; we interleave, so split the last long.
        for (int i = 0; i < SOLID_LONGS - 1; i++) {
            MemoryUtil.memPutLong(ptr + (long) i * Long.BYTES, solid[i]);
        }
        MemoryUtil.memPutInt(ptr + (long) (SOLID_LONGS - 1) * Long.BYTES, (int) solid[SOLID_LONGS - 1]);
    }

    /**
     * Populate {@link #storages} with the 3x3x3 cube neighborhood around {@code (sx, sy, sz)}.
     * Slot layout: {@code (dx + 1) + (dz + 1) * 3 + (dy + 1) * 9}. {@link #collectSection} already
     * zeroed the array; unloaded/out-of-range slots must remain null.
     */
    abstract void fillStorages(int sx, int sy, int sz);
}
