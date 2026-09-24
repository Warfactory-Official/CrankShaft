package dev.engine_room.flywheel.backend.engine;

import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Point and segment block-light sources the light engine never sees (LambDynamicLights-style), max'd into instance
 * block light on the GPU ({@code internal/render_origin.glsl}). Beyond {@link #MAX_LIGHTS} / {@link #MAX_SEGMENTS},
 * the nearest to the camera win.
 */
public final class DynamicLights {
    static final int CELLS = 512;
    static final int MAX_LIGHTS = 128;
    // Every vertex tests every segment: few, long (beams).
    static final int MAX_SEGMENTS = 16;
    // A light reaches at most 3 cells per axis.
    private static final int MAX_ENTRIES = MAX_LIGHTS * 27;
    private static final int CELLS_OFFSET = 64;
    private static final int LIGHTS_OFFSET = CELLS_OFFSET + CELLS * 4;
    private static final int ENTRIES_OFFSET = LIGHTS_OFFSET + MAX_LIGHTS * 16;
    private static final int SEGMENTS_OFFSET = ENTRIES_OFFSET + MAX_ENTRIES * 2;
    // _FlwRenderOrigin; <= 16 KiB, the smallest max uniform block.
    static final int UNIFORM_SIZE = SEGMENTS_OFFSET + MAX_SEGMENTS * 32;
    private static final double RADIUS = 7.75;

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    private static final Sink SINK = new Sink() {
        @Override
        public void accept(double x, double y, double z, int luminance) {
            add(x, y, z, luminance);
        }

        @Override
        public void segment(double ax, double ay, double az, double bx, double by, double bz, int luminance) {
            addSegment(ax, ay, az, bx, by, bz, luminance);
        }
    };
    private static final int[] CELL_START = new int[CELLS];
    private static final int[] CELL_COUNT = new int[CELLS];
    private static final int[] CELL_LAST = new int[CELLS];
    private static final short[] ENTRIES = new short[MAX_ENTRIES];

    private static double[] xs = new double[16];
    private static double[] ys = new double[16];
    private static double[] zs = new double[16];
    private static int[] luminances = new int[16];
    private static int[] order = new int[16];
    private static int collected;
    private static int count;
    // a.xyz, b.xyz per segment.
    private static double[] segments = new double[6 * 4];
    private static int[] segmentLuminances = new int[4];
    private static int[] segmentOrder = new int[4];
    private static int segmentsCollected;
    private static int segmentCount;

    private DynamicLights() {
    }

    /**
     * Any thread, typically client init. Sources are polled on the render thread once per engine frame.
     */
    public static void register(Source source) {
        SOURCES.add(source);
    }

    static void collect(Vec3 camera) {
        collected = 0;
        segmentsCollected = 0;
        for (Source source : SOURCES) {
            source.collect(SINK);
        }
        for (int i = 0; i < segmentsCollected; i++) {
            segmentOrder[i] = i;
        }
        if (segmentsCollected > MAX_SEGMENTS) {
            IntArrays.quickSort(segmentOrder, 0, segmentsCollected, (a, b) -> Double.compare(
                    segmentDistanceSqr(a, camera), segmentDistanceSqr(b, camera)));
        }
        segmentCount = Math.min(segmentsCollected, MAX_SEGMENTS);
        for (int i = 0; i < collected; i++) {
            order[i] = i;
        }
        if (collected > MAX_LIGHTS) {
            IntArrays.quickSort(order, 0, collected, (a, b) -> Double.compare(distanceSqr(a, camera),
                    distanceSqr(b, camera)));
        }
        count = Math.min(collected, MAX_LIGHTS);
        if (count == 0) {
            return;
        }
        Arrays.fill(CELL_COUNT, 0);
        Arrays.fill(CELL_LAST, -1);
        for (int i = 0; i < count; i++) {
            forEachReachedKey(i, true);
        }
        int start = 0;
        for (int key = 0; key < CELLS; key++) {
            CELL_START[key] = start;
            start += CELL_COUNT[key];
            CELL_COUNT[key] = 0;
        }
        Arrays.fill(CELL_LAST, -1);
        for (int i = 0; i < count; i++) {
            forEachReachedKey(i, false);
        }
    }

    // Keys of the cells holding a block whose centre is within RADIUS of light i; once per key and light.
    private static void forEachReachedKey(int i, boolean counting) {
        int light = order[i];
        double x = xs[light], y = ys[light], z = zs[light];
        int minX = Mth.ceil(x - RADIUS - 0.5) >> 3, maxX = Mth.floor(x + RADIUS - 0.5) >> 3;
        int minY = Mth.ceil(y - RADIUS - 0.5) >> 3, maxY = Mth.floor(y + RADIUS - 0.5) >> 3;
        int minZ = Mth.ceil(z - RADIUS - 0.5) >> 3, maxZ = Mth.floor(z + RADIUS - 0.5) >> 3;
        for (int cx = minX; cx <= maxX; cx++) {
            double dx = axisDistance(x, cx);
            for (int cy = minY; cy <= maxY; cy++) {
                double dy = axisDistance(y, cy);
                for (int cz = minZ; cz <= maxZ; cz++) {
                    double dz = axisDistance(z, cz);
                    if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) {
                        continue;
                    }
                    int key = key(cx, cy, cz);
                    if (CELL_LAST[key] == i) {
                        continue;
                    }
                    CELL_LAST[key] = i;
                    if (counting) {
                        CELL_COUNT[key]++;
                    } else {
                        ENTRIES[CELL_START[key] + CELL_COUNT[key]++] = (short) i;
                    }
                }
            }
        }
    }

    // From p to the nearest block centre in cell c.
    private static double axisDistance(double p, int c) {
        return Math.max(0, Math.max(c * 8 + 0.5 - p, p - (c * 8 + 7.5)));
    }

    /**
     * {@code _FlwRenderOrigin} from {@code _flw_dynamicLightCount} on.
     */
    static void write(long ptr, Vec3i origin) {
        MemoryUtil.memPutInt(ptr + 20, count);
        MemoryUtil.memPutInt(ptr + 24, segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            int segment = segmentOrder[i];
            long at = ptr + SEGMENTS_OFFSET + i * 32L;
            for (int end = 0; end < 2; end++) {
                int from = segment * 6 + end * 3;
                MemoryUtil.memPutFloat(at + end * 16L, (float) (segments[from] - origin.getX()));
                MemoryUtil.memPutFloat(at + end * 16L + 4, (float) (segments[from + 1] - origin.getY()));
                MemoryUtil.memPutFloat(at + end * 16L + 8, (float) (segments[from + 2] - origin.getZ()));
            }
            MemoryUtil.memPutFloat(at + 12, segmentLuminances[segment]);
        }
        if (count == 0) {
            return;
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            int light = order[i];
            float x = (float) (xs[light] - origin.getX());
            float y = (float) (ys[light] - origin.getY());
            float z = (float) (zs[light] - origin.getZ());
            long at = ptr + LIGHTS_OFFSET + i * 16L;
            MemoryUtil.memPutFloat(at, x);
            MemoryUtil.memPutFloat(at + 4, y);
            MemoryUtil.memPutFloat(at + 8, z);
            MemoryUtil.memPutFloat(at + 12, luminances[light]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        MemoryUtil.memPutFloat(ptr + 32, minX - (float) RADIUS);
        MemoryUtil.memPutFloat(ptr + 36, minY - (float) RADIUS);
        MemoryUtil.memPutFloat(ptr + 40, minZ - (float) RADIUS);
        MemoryUtil.memPutFloat(ptr + 48, maxX + (float) RADIUS);
        MemoryUtil.memPutFloat(ptr + 52, maxY + (float) RADIUS);
        MemoryUtil.memPutFloat(ptr + 56, maxZ + (float) RADIUS);
        int entries = 0;
        for (int key = 0; key < CELLS; key++) {
            MemoryUtil.memPutInt(ptr + CELLS_OFFSET + key * 4L, CELL_START[key] | CELL_COUNT[key] << 16);
            entries += CELL_COUNT[key];
        }
        // Little-endian: entry i = 16-bit half (i & 1) of uint i >> 1.
        for (int i = 0; i < entries; i++) {
            MemoryUtil.memPutShort(ptr + ENTRIES_OFFSET + i * 2L, ENTRIES[i]);
        }
    }

    // = render_origin.glsl _flw_dynamicLightKey
    private static int key(int cx, int cy, int cz) {
        return ((cx * 73856093) ^ (cy * 19349663) ^ (cz * 83492791)) & (CELLS - 1);
    }

    private static double distanceSqr(int light, Vec3 camera) {
        return camera.distanceToSqr(xs[light], ys[light], zs[light]);
    }

    private static double segmentDistanceSqr(int segment, Vec3 camera) {
        int at = segment * 6;
        double ax = segments[at], ay = segments[at + 1], az = segments[at + 2];
        double abx = segments[at + 3] - ax, aby = segments[at + 4] - ay, abz = segments[at + 5] - az;
        double length = abx * abx + aby * aby + abz * abz;
        double t = length == 0 ? 0 : Mth.clamp(((camera.x - ax) * abx + (camera.y - ay) * aby
                + (camera.z - az) * abz) / length, 0, 1);
        return camera.distanceToSqr(ax + t * abx, ay + t * aby, az + t * abz);
    }

    private static void addSegment(double ax, double ay, double az, double bx, double by, double bz, int luminance) {
        if (segmentsCollected == segmentLuminances.length) {
            int capacity = segmentsCollected * 2;
            segments = Arrays.copyOf(segments, capacity * 6);
            segmentLuminances = Arrays.copyOf(segmentLuminances, capacity);
            segmentOrder = Arrays.copyOf(segmentOrder, capacity);
        }
        int at = segmentsCollected * 6;
        segments[at] = ax;
        segments[at + 1] = ay;
        segments[at + 2] = az;
        segments[at + 3] = bx;
        segments[at + 4] = by;
        segments[at + 5] = bz;
        segmentLuminances[segmentsCollected++] = luminance;
    }

    private static void add(double x, double y, double z, int luminance) {
        if (collected == xs.length) {
            int capacity = collected * 2;
            xs = Arrays.copyOf(xs, capacity);
            ys = Arrays.copyOf(ys, capacity);
            zs = Arrays.copyOf(zs, capacity);
            luminances = Arrays.copyOf(luminances, capacity);
            order = Arrays.copyOf(order, capacity);
        }
        xs[collected] = x;
        ys[collected] = y;
        zs[collected] = z;
        luminances[collected] = luminance;
        collected++;
    }

    @FunctionalInterface
    public interface Source {
        void collect(Sink sink);
    }

    public interface Sink {
        /**
         * @param luminance 1-15 at the source, falling off by 15 per 7.75 blocks.
         */
        void accept(double x, double y, double z, int luminance);

        /**
         * {@link #accept} at the nearest point of segment {@code a}-{@code b}.
         */
        void segment(double ax, double ay, double az, double bx, double by, double bz, int luminance);
    }
}
