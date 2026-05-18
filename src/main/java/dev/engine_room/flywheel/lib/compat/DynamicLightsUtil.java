package dev.engine_room.flywheel.lib.compat;

import dev.engine_room.flywheel.backend.engine.SectionPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.lwjgl.system.MemoryUtil;

public final class DynamicLightsUtil {
    // [sx, sy, sz, effLevel, effRadius, effRadiusSq]
    public static final int STRIDE = 6;

    // Crossover where per-voxel iteration amortizes memory ops better than per-source. Per-source
    // visits ~sphere_volume × writes; per-voxel visits 5832 × N max-merge in registers with one
    // memGet/memPut per voxel. Per-voxel wins once N ≥ 3.
    public static final int PER_SOURCE_THRESHOLD = 2;

    private DynamicLightsUtil() {
    }

    /**
     * Add every section coord in the source's AABB to {@code out}. Both ends are inclusive.
     */
    public static void markSectionsForSource(double sx, double sy, double sz, double radius, LongOpenHashSet out) {
        int sxMin = ((int) Math.floor(sx - radius)) >> 4;
        int sxMax = ((int) Math.floor(sx + radius)) >> 4;
        int syMin = ((int) Math.floor(sy - radius)) >> 4;
        int syMax = ((int) Math.floor(sy + radius)) >> 4;
        int szMin = ((int) Math.floor(sz - radius)) >> 4;
        int szMax = ((int) Math.floor(sz + radius)) >> 4;
        for (int x = sxMin; x <= sxMax; x++) {
            for (int y = syMin; y <= syMax; y++) {
                for (int z = szMin; z <= szMax; z++) {
                    out.add(SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    /**
     * Dispatch the already-pre-culled {@code active} list to per-source or per-voxel apply.
     * Callers are responsible for AABB-filtering and producing the {@link #STRIDE}-stride row format;
     * CDL builds {@code active} per section from its source {@code Set}, OF builds it from the
     * main-thread snapshot.
     */
    public static void applyActive(long lightBase, int sectionX, int sectionY, int sectionZ,
                                   double[] active, int activeCount) {
        if (activeCount == 0) {
            return;
        }

        final int xBlockMin = sectionX << 4;
        final int yBlockMin = sectionY << 4;
        final int zBlockMin = sectionZ << 4;
        // Section halo runs gx ∈ [xBlockMin - 1, xBlockMin + 16]; offset is (gx - xBlockMin + 1) on each axis.
        final int haloMinX = xBlockMin - 1;
        final int haloMaxX = xBlockMin + 16;
        final int haloMinY = yBlockMin - 1;
        final int haloMaxY = yBlockMin + 16;
        final int haloMinZ = zBlockMin - 1;
        final int haloMaxZ = zBlockMin + 16;

        if (activeCount / STRIDE <= PER_SOURCE_THRESHOLD) {
            applyPerSource(lightBase, xBlockMin, yBlockMin, zBlockMin,
                    haloMinX, haloMaxX, haloMinY, haloMaxY, haloMinZ, haloMaxZ,
                    active, activeCount);
        } else {
            applyPerVoxel(lightBase, xBlockMin, yBlockMin, zBlockMin, active, activeCount);
        }
    }

    // For N_active ≤ 2: iterate each source's sphere voxels.
    private static void applyPerSource(long lightBase, int xBlockMin, int yBlockMin, int zBlockMin,
                                       int haloMinX, int haloMaxX, int haloMinY, int haloMaxY,
                                       int haloMinZ, int haloMaxZ,
                                       double[] active, int activeCount) {
        for (int s = 0; s < activeCount; s += STRIDE) {
            double sx = active[s];
            double sy = active[s + 1];
            double sz = active[s + 2];
            double effLevel = active[s + 3];
            double effRadius = active[s + 4];
            double effRadiusSq = active[s + 5];

            int gxMin = Math.max(haloMinX, (int) Math.floor(sx - effRadius));
            int gxMax = Math.min(haloMaxX, (int) Math.ceil(sx + effRadius));
            int gyMin = Math.max(haloMinY, (int) Math.floor(sy - effRadius));
            int gyMax = Math.min(haloMaxY, (int) Math.ceil(sy + effRadius));
            int gzMin = Math.max(haloMinZ, (int) Math.floor(sz - effRadius));
            int gzMax = Math.min(haloMaxZ, (int) Math.ceil(sz + effRadius));

            for (int gy = gyMin; gy <= gyMax; gy++) {
                double dy = (gy + 0.5) - sy;
                double dy2 = dy * dy;
                if (dy2 > effRadiusSq) {
                    continue;
                }
                int yStride = (gy - yBlockMin + 1) * 18 * 18;
                for (int gz = gzMin; gz <= gzMax; gz++) {
                    double dz = (gz + 0.5) - sz;
                    double dyz2 = dy2 + dz * dz;
                    if (dyz2 > effRadiusSq) {
                        continue;
                    }
                    int rowBase = yStride + (gz - zBlockMin + 1) * 18;
                    for (int gx = gxMin; gx <= gxMax; gx++) {
                        double dx = (gx + 0.5) - sx;
                        double d2 = dyz2 + dx * dx;
                        if (d2 > effRadiusSq) {
                            continue;
                        }
                        int dynBlock = (int) ((1.0 - Math.sqrt(d2) / effRadius) * effLevel);
                        if (dynBlock <= 0) {
                            continue;
                        }
                        if (dynBlock > 15) {
                            dynBlock = 15;
                        }
                        mergeVoxel(lightBase + rowBase + (gx - xBlockMin + 1), dynBlock);
                    }
                }
            }
        }
    }

    // For N_active ≥ 3: iterate every voxel of the section halo, max-merge across active
    // sources in registers, single memGet/memPut per voxel.
    private static void applyPerVoxel(long lightBase, int xBlockMin, int yBlockMin, int zBlockMin,
                                      double[] active, int activeCount) {
        for (int y = -1; y < 17; y++) {
            double vy = (yBlockMin + y) + 0.5;
            int yStride = (y + 1) * 18 * 18;
            for (int z = -1; z < 17; z++) {
                double vz = (zBlockMin + z) + 0.5;
                int rowBase = yStride + (z + 1) * 18;
                for (int x = -1; x < 17; x++) {
                    double vx = (xBlockMin + x) + 0.5;

                    double maxLight = 0.0;
                    for (int s = 0; s < activeCount; s += STRIDE) {
                        double dx = vx - active[s];
                        double dy = vy - active[s + 1];
                        double dz = vz - active[s + 2];
                        double d2 = dx * dx + dy * dy + dz * dz;
                        double effRadiusSq = active[s + 5];
                        if (d2 > effRadiusSq) {
                            continue;
                        }
                        double level = (1.0 - Math.sqrt(d2) / active[s + 4]) * active[s + 3];
                        if (level > maxLight) {
                            maxLight = level;
                        }
                    }

                    if (maxLight <= 0) {
                        continue;
                    }
                    int dynBlock = (int) maxLight;
                    if (dynBlock > 15) {
                        dynBlock = 15;
                    }
                    mergeVoxel(lightBase + rowBase + (x + 1), dynBlock);
                }
            }
        }
    }

    private static void mergeVoxel(long bytePtr, int dynBlock) {
        byte existing = MemoryUtil.memGetByte(bytePtr);
        int existingBlock = existing & 0xF;
        if (dynBlock > existingBlock) {
            int skyPacked = existing & 0xF0;
            MemoryUtil.memPutByte(bytePtr, (byte) (skyPacked | dynBlock));
        }
    }
}
