package dev.engine_room.flywheel.iris.engine;

/**
 * Capacity policy for asynchronous capture demand. Owners supply the maximum demand of each completed-readback
 * batch and keep the selected pool size in their draw state. Pool replacement belongs to the next capture boundary;
 * observing demand never changes a GPU resource.
 */
final class DeferredDemandBudget {
    private static final long QUIET_NANOS = 3_000_000_000L;
    private boolean poolQuiet;
    private long poolQuietSince;
    private long poolQuietPeak;

    static long nodesForDemand(long demand, long maximum, long minimum, int granule) {
        long granules = (demand + demand / 4 + granule - 1) / granule;
        return Math.min(maximum, Math.max(minimum, granules * granule));
    }

    void resetQuietPeriod() {
        poolQuiet = false;
    }

    long poolTarget(long current, long wanted, long maximum, long now) {
        if (wanted > current) {
            poolQuiet = false;
            if (Boolean.parseBoolean(System.getProperty("crankshaft.iris.geometricPoolGrowth", "true"))) {
                return Math.min(maximum, Math.max(wanted, current + current / 2));
            }
            return wanted;
        }
        if (wanted * 2 > current) {
            poolQuiet = false;
            return current;
        }
        if (!poolQuiet) {
            poolQuiet = true;
            poolQuietSince = now;
            poolQuietPeak = wanted;
        } else poolQuietPeak = Math.max(poolQuietPeak, wanted);
        if (now - poolQuietSince < QUIET_NANOS) return current;
        poolQuiet = false;
        return Math.min(maximum, poolQuietPeak);
    }
}
