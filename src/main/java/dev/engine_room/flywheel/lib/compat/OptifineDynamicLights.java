package dev.engine_room.flywheel.lib.compat;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.EnumSkyBlock;
import net.optifine.DynamicLight;
import net.optifine.DynamicLights;
import net.optifine.DynamicLightsMap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.function.LongPredicate;

public final class OptifineDynamicLights implements DynamicLightProvider {
    public static final OptifineDynamicLights INSTANCE = new OptifineDynamicLights();

    // net.optifine.DynamicLights.MAX_DIST / MAX_DIST_SQ
    private static final double MAX_RADIUS = 7.5;
    private static final double MAX_RADIUS_SQ = 56.25;
    // Underwater (non-clear-water) effective radius from OF: d² gets doubled before comparing
    // to 56.25, equivalent to halving the radius squared.
    private static final double UW_RADIUS = MAX_RADIUS / Math.sqrt(2.0);
    private static final double UW_RADIUS_SQ = MAX_RADIUS_SQ / 2.0;

    // OF ships Config in the default package, which packaged source can't import.
    private static final MethodHandle IS_DYNAMIC_LIGHTS;
    private static final MethodHandle IS_CLEAR_WATER;
    static {
        try {
            Class<?> cfg = Class.forName("Config");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType bool0 = MethodType.methodType(boolean.class);
            IS_DYNAMIC_LIGHTS = lookup.findStatic(cfg, "isDynamicLights", bool0);
            IS_CLEAR_WATER = lookup.findStatic(cfg, "isClearWater", bool0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Optifine present but Config.{isDynamicLights,isClearWater} not resolvable", e);
        }
    }

    private static final LongOpenHashSet PREV_AFFECTED = new LongOpenHashSet();
    private static long prevFingerprint;
    private static boolean prevFingerprintValid;

    // Main-thread-written, worker-read. Same row layout as DynamicLightsUtil.STRIDE.
    // effRadius/effRadiusSq are precomputed so the apply path doesn't need ClearWater state.
    private static double[] snapshot = new double[0];
    private static int snapshotCount;
    private static long lastSnapshotTimeUpdateMs = -1L;

    private OptifineDynamicLights() {
    }

    private static boolean isClearWater() {
        try {
            return (boolean) IS_CLEAR_WATER.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    @Override
    public boolean enabled() {
        try {
            return (boolean) IS_DYNAMIC_LIGHTS.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    @Override
    public void captureSnapshot() {
        if (!enabled()) {
            return;
        }
        // OF only mutates source data inside DynamicLights.update(), which bumps timeUpdateMs
        // to System.currentTimeMillis() each time it runs (gated at 50ms in OF). If the timer
        // hasn't advanced, our snapshot is still current — skip the rebuild.
        long currentTimeUpdateMs = DynamicLights.timeUpdateMs;
        if (currentTimeUpdateMs == lastSnapshotTimeUpdateMs) {
            return;
        }
        lastSnapshotTimeUpdateMs = currentTimeUpdateMs;

        DynamicLightsMap map = DynamicLights.mapDynamicLights;
        int currentSize = map == null ? 0 : map.size();
        if (map == null || currentSize == 0) {
            snapshotCount = 0;
            return;
        }
        int needed = currentSize * DynamicLightsUtil.STRIDE;
        if (snapshot.length < needed) {
            snapshot = new double[Math.max(needed, DynamicLightsUtil.STRIDE * 16)];
        }
        boolean cw = isClearWater();
        List<DynamicLight> list = map.valueList();
        int count = 0;
        for (DynamicLight dl : list) {
            int level = dl.getLastLightLevel();
            if (level <= 0) {
                continue;
            }
            double effLevel = level;
            double effRadius = MAX_RADIUS;
            double effRadiusSq = MAX_RADIUS_SQ;
            if (dl.isUnderwater() && !cw) {
                effLevel = level - 2.0;
                if (effLevel <= 0) {
                    continue;
                }
                effRadius = UW_RADIUS;
                effRadiusSq = UW_RADIUS_SQ;
            }
            // OF stores entity.pos - 0.5; add 0.5 back so the shared apply path samples at
            // block center uniformly with CDL (which stores entity.pos directly).
            snapshot[count] = dl.getLastPosX() + 0.5;
            snapshot[count + 1] = dl.getLastPosY() + 0.5;
            snapshot[count + 2] = dl.getLastPosZ() + 0.5;
            snapshot[count + 3] = effLevel;
            snapshot[count + 4] = effRadius;
            snapshot[count + 5] = effRadiusSq;
            count += DynamicLightsUtil.STRIDE;
        }
        snapshotCount = count;
    }

    private static long fingerprint() {
        long h = 1L;
        for (int i = 0; i < snapshotCount; i++) {
            h = h * 31 + Double.doubleToRawLongBits(snapshot[i]);
        }
        return h;
    }

    @Override
    public void notifyAffected(LongPredicate isVisualized, VisualizationManagerImpl manager) {
        if (!enabled()) {
            return;
        }
        long fp = fingerprint();
        if (prevFingerprintValid && fp == prevFingerprint) {
            return;
        }
        prevFingerprint = fp;
        prevFingerprintValid = true;

        LongOpenHashSet newAffected = new LongOpenHashSet();
        for (int s = 0; s < snapshotCount; s += DynamicLightsUtil.STRIDE) {
            DynamicLightsUtil.markSectionsForSource(
                    snapshot[s], snapshot[s + 1], snapshot[s + 2], snapshot[s + 4], newAffected);
        }

        newAffected.forEach(pos -> {
            if (isVisualized.test(pos)) {
                manager.onLightUpdate(pos, EnumSkyBlock.BLOCK);
            }
        });
        PREV_AFFECTED.forEach(pos -> {
            if (!newAffected.contains(pos) && isVisualized.test(pos)) {
                manager.onLightUpdate(pos, EnumSkyBlock.BLOCK);
            }
        });

        PREV_AFFECTED.clear();
        PREV_AFFECTED.addAll(newAffected);
    }

    @Override
    public void applyToSection(long lightBase, int sectionX, int sectionY, int sectionZ) {
        if (!enabled()) {
            return;
        }
        if (snapshotCount == 0) {
            return;
        }

        final int xBlockMin = sectionX << 4;
        final int yBlockMin = sectionY << 4;
        final int zBlockMin = sectionZ << 4;
        final int haloMinX = xBlockMin - 1;
        final int haloMaxX = xBlockMin + 16;
        final int haloMinY = yBlockMin - 1;
        final int haloMaxY = yBlockMin + 16;
        final int haloMinZ = zBlockMin - 1;
        final int haloMaxZ = zBlockMin + 16;

        double[] active = new double[snapshotCount];
        int activeCount = 0;
        for (int s = 0; s < snapshotCount; s += DynamicLightsUtil.STRIDE) {
            double sx = snapshot[s];
            double sy = snapshot[s + 1];
            double sz = snapshot[s + 2];
            double radius = snapshot[s + 4];
            if (sx + radius < haloMinX || sx - radius > haloMaxX
                    || sy + radius < haloMinY || sy - radius > haloMaxY
                    || sz + radius < haloMinZ || sz - radius > haloMaxZ) {
                continue;
            }
            active[activeCount] = sx;
            active[activeCount + 1] = sy;
            active[activeCount + 2] = sz;
            active[activeCount + 3] = snapshot[s + 3];
            active[activeCount + 4] = radius;
            active[activeCount + 5] = snapshot[s + 5];
            activeCount += DynamicLightsUtil.STRIDE;
        }

        DynamicLightsUtil.applyActive(lightBase, sectionX, sectionY, sectionZ, active, activeCount);
    }
}
