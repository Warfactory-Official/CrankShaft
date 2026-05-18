package dev.engine_room.flywheel.lib.math;

public final class MoreMath {
    private MoreMath() {
    }

    /**
     * The circumsphere of a cube has a radius of sqrt(3) / 2 * sideLength.
     */
    public static final float SQRT_3_OVER_2 = (float) (Math.sqrt(3.0) / 2.0);

    public static int align32(int size) {
        return (size + 31) & ~31;
    }

    public static int align16(int size) {
        return (size + 15) & ~15;
    }

    public static int align4(int size) {
        return (size + 3) & ~3;
    }

    public static int alignPot(int size, int to) {
        return (size + (to - 1)) & ~(to - 1);
    }

    public static int ceilingDiv(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    public static long ceilingDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    public static long ceilLong(double d) {
        return (long) Math.ceil(d);
    }

    public static long ceilLong(float f) {
        return (long) Math.ceil(f);
    }

    public static long align4(long size) {
        return (size + 3) & ~3L;
    }

    public static int log2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    public static boolean isPowerOfTwo(int value) {
        return value != 0 && (value & (value - 1)) == 0;
    }

    public static int ceillog2(int value) {
        return value == 0 ? 0 : isPowerOfTwo(value) ? log2(value) : log2(value) + 1;
    }
}
