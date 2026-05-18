package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.lwjgl.system.MemoryUtil;

public abstract class ColoredLitInstance extends AbstractInstance implements FlatLit {
    static final int OFF_RGBA = 0;
    static final int OFF_LIGHT = 8;

    public ColoredLitInstance(InstanceType<? extends ColoredLitInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public ColoredLitInstance colorArgb(int argb) {
        return color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >> 24) & 0xFF);
    }

    public ColoredLitInstance colorRgb(int rgb) {
        return color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    public ColoredLitInstance color(int red, int green, int blue, int alpha) {
        return color((byte) red, (byte) green, (byte) blue, (byte) alpha);
    }

    public ColoredLitInstance color(int red, int green, int blue) {
        long p = slabPtr() + OFF_RGBA;
        MemoryUtil.memPutByte(p, (byte) red);
        MemoryUtil.memPutByte(p + 1, (byte) green);
        MemoryUtil.memPutByte(p + 2, (byte) blue);
        return this;
    }

    public ColoredLitInstance color(byte red, byte green, byte blue, byte alpha) {
        int packed = (red & 0xFF) | ((green & 0xFF) << 8) | ((blue & 0xFF) << 16) | ((alpha & 0xFF) << 24);
        MemoryUtil.memPutInt(slabPtr() + OFF_RGBA, packed);
        return this;
    }

    public ColoredLitInstance color(byte red, byte green, byte blue) {
        long p = slabPtr() + OFF_RGBA;
        MemoryUtil.memPutByte(p, red);
        MemoryUtil.memPutByte(p + 1, green);
        MemoryUtil.memPutByte(p + 2, blue);
        return this;
    }

    public ColoredLitInstance color(float red, float green, float blue, float alpha) {
        return color((byte) (red * 255f), (byte) (green * 255f), (byte) (blue * 255f), (byte) (alpha * 255f));
    }

    public ColoredLitInstance color(float red, float green, float blue) {
        return color((byte) (red * 255f), (byte) (green * 255f), (byte) (blue * 255f));
    }

    @Override
    public ColoredLitInstance light(int light) {
        ExtraMemoryOps.put2x16(slabPtr() + OFF_LIGHT, light);
        return this;
    }
}
