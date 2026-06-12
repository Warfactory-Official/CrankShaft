package dev.engine_room.flywheel.backend.engine;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.lwjgl.system.MemoryUtil;

/**
 * Shared light/solid-bit packing for an 18x18x18 section neighborhood. The vanilla light engine
 * fetch is in {@link VanillaLightDataCollector}.
 */
public abstract class LightDataCollector {
    static final int SOLID_LONGS = (LightStorage.BLOCKS_PER_SECTION + 63) >>> 6;

    final int defaultSkyLight;
    final LevelChunkSection[] sections = new LevelChunkSection[27];
    final DataLayer[] blockLight = new DataLayer[27];
    final DataLayer[] skyLight = new DataLayer[27];
    final long[] solid = new long[SOLID_LONGS];

    final LayerLightEventListener blockEngine;
    final LayerLightEventListener skyEngine;

    LightDataCollector(LevelAccessor level) {
        this.defaultSkyLight = level.dimensionType().hasSkyLight() ? 15 : 0;
        LevelLightEngine lightEngine = level.getLightEngine();
        this.blockEngine = lightEngine.getLayerListener(LightLayer.BLOCK);
        this.skyEngine = lightEngine.getLayerListener(LightLayer.SKY);
    }

    public static LightDataCollector of(LevelAccessor level) {
        return new VanillaLightDataCollector(level);
    }

    public final void collectSection(long ptr, long section) {
        final int sx = SectionPos.x(section);
        final int sy = SectionPos.y(section);
        final int sz = SectionPos.z(section);

        for (int i = 0; i < 27; i++) {
            sections[i] = null;
            blockLight[i] = null;
            skyLight[i] = null;
        }
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

                    final int slot = xi + zi * 3 + yi * 9;
                    LevelChunkSection s = sections[slot];
                    int block;
                    int sky;
                    if (s == null) {
                        block = 0;
                        sky = defaultSky;
                    } else {
                        BlockState bs = s.getBlockState(lx, ly, lz);
                        if (bs.isSolidRender()) {
                            solid[idx >>> 6] |= 1L << (idx & 63);
                        }
                        DataLayer blockArr = blockLight[slot];
                        block = blockArr != null ? blockArr.get(lx, ly, lz) : 0;
                        DataLayer skyArr = skyLight[slot];
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
        // (already written above) and zero them out -- upstream sidesteps this by running
        // collectSolidData before collectLightData; we interleave, so split the last long.
        for (int i = 0; i < SOLID_LONGS - 1; i++) {
            MemoryUtil.memPutLong(ptr + (long) i * Long.BYTES, solid[i]);
        }
        MemoryUtil.memPutInt(ptr + (long) (SOLID_LONGS - 1) * Long.BYTES, (int) solid[SOLID_LONGS - 1]);
    }

    /**
     * Populate {@link #sections}, {@link #blockLight}, and {@link #skyLight} with the 3x3x3 cube
     * neighborhood around {@code (sx, sy, sz)}. Slot layout: {@code (dx + 1) + (dz + 1) * 3 + (dy + 1) * 9}.
     * {@link #collectSection} already zeroed the arrays; unloaded/out-of-range slots must remain null.
     */
    abstract void fillStorages(int sx, int sy, int sz);
}
