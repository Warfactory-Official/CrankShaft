package dev.engine_room.flywheel.lib.compat;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import toni.sodiumdynamiclights.DynamicLightSource;
import toni.sodiumdynamiclights.SodiumDynamicLights;
import toni.sodiumdynamiclights.config.DynamicLightsConfig;

import java.util.Set;
import java.util.function.LongPredicate;

public final class CeleriatsDynamicLights implements DynamicLightProvider {
    public static final CeleriatsDynamicLights INSTANCE = new CeleriatsDynamicLights();

    private static final double MAX_RADIUS = 7.75;
    private static final double MAX_RADIUS_SQ = MAX_RADIUS * MAX_RADIUS;

    // its mod instance, always nonnull when the mod is loaded
    private static final SodiumDynamicLights SDL = SodiumDynamicLights.get();

    private static final LongOpenHashSet PREV_AFFECTED = new LongOpenHashSet();
    private static long prevFingerprint;
    private static boolean prevFingerprintValid;

    private CeleriatsDynamicLights() {
    }

    @Override
    public boolean enabled() {
        return DynamicLightsConfig.dynamicLightsMode.isEnabled();
    }

    private static long fingerprint(Set<DynamicLightSource> sources) {
        long h = 1L;
        for (DynamicLightSource src : sources) {
            int lum = src.sdl$getLuminance();
            if (lum <= 0) {
                continue;
            }
            h = h * 31 + Double.doubleToRawLongBits(src.sdl$getDynamicLightX());
            h = h * 31 + Double.doubleToRawLongBits(src.sdl$getDynamicLightY());
            h = h * 31 + Double.doubleToRawLongBits(src.sdl$getDynamicLightZ());
            h = h * 31 + lum;
        }
        return h;
    }

    @Override
    public void notifyAffected(LongPredicate isVisualized, VisualizationManagerImpl manager) {
        if (!enabled()) {
            return;
        }
        Set<DynamicLightSource> sources = SDL.dynamicLightSources;
        long fp = fingerprint(sources);
        if (prevFingerprintValid && fp == prevFingerprint) {
            return;
        }
        prevFingerprint = fp;
        prevFingerprintValid = true;

        LongOpenHashSet newAffected = new LongOpenHashSet();
        for (DynamicLightSource src : sources) {
            int lum = src.sdl$getLuminance();
            if (lum <= 0) {
                continue;
            }
            DynamicLightsUtil.markSectionsForSource(
                    src.sdl$getDynamicLightX(),
                    src.sdl$getDynamicLightY(),
                    src.sdl$getDynamicLightZ(),
                    MAX_RADIUS, newAffected);
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
        Set<DynamicLightSource> sources = SDL.dynamicLightSources;
        if (sources.isEmpty()) {
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

        // if jit is smart enough this can be optimized to stack alloc
        double[] active = new double[sources.size() * DynamicLightsUtil.STRIDE];
        int activeCount = 0;
        for (DynamicLightSource src : sources) {
            int lum = src.sdl$getLuminance();
            if (lum <= 0) {
                continue;
            }
            double sx = src.sdl$getDynamicLightX();
            double sy = src.sdl$getDynamicLightY();
            double sz = src.sdl$getDynamicLightZ();
            if (sx + MAX_RADIUS < haloMinX || sx - MAX_RADIUS > haloMaxX
                    || sy + MAX_RADIUS < haloMinY || sy - MAX_RADIUS > haloMaxY
                    || sz + MAX_RADIUS < haloMinZ || sz - MAX_RADIUS > haloMaxZ) {
                continue;
            }
            active[activeCount] = sx;
            active[activeCount + 1] = sy;
            active[activeCount + 2] = sz;
            active[activeCount + 3] = lum;
            active[activeCount + 4] = MAX_RADIUS;
            active[activeCount + 5] = MAX_RADIUS_SQ;
            activeCount += DynamicLightsUtil.STRIDE;
        }

        DynamicLightsUtil.applyActive(lightBase, sectionX, sectionY, sectionZ, active, activeCount);
    }

    @Override
    public int applyLightAt(BlockPos pos, int packedLight) {
        if (!enabled()) {
            return packedLight;
        }
        return SDL.getLightmapWithDynamicLight(pos, packedLight);
    }

    @Override
    public int getLightForEntity(Entity entity, BlockPos samplePos) {
        if (!enabled()) {
            return DynamicLightProvider.super.getLightForEntity(entity, samplePos);
        }
        int packedLight = entity.world.getCombinedLight(samplePos, 0);
        double posLight = SDL.getDynamicLightLevel(samplePos);
        int entityLuminance = ((DynamicLightSource) entity).sdl$getLuminance();
        return SDL.getLightmapWithDynamicLight(Math.max(posLight, entityLuminance), packedLight);
    }
}
