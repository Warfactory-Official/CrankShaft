package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.lwjgl.system.MemoryUtil;

/**
 * One nameplate element (glyph quad, style rect, or background box) billboarded about the shared label {@code anchor}.
 */
public class GlyphInstance extends AbstractInstance implements FlatLit {
    static final int OFF_RGBA = 0;
    static final int OFF_LIGHT = 4;
    static final int OFF_ANCHOR = 8;
    static final int OFF_PLACEMENT = 20;
    static final int OFF_UV_REGION = 40;
    static final int OFF_DEPTH = 56;

    public GlyphInstance(InstanceType<? extends GlyphInstance> type, InstanceHandle handle) {
        super(type, handle);
    }

    public GlyphInstance color(int red, int green, int blue, int alpha) {
        int packed = (red & 0xFF) | ((green & 0xFF) << 8) | ((blue & 0xFF) << 16) | ((alpha & 0xFF) << 24);
        MemoryUtil.memPutInt(slabPtr() + OFF_RGBA, packed);
        return this;
    }

    @Override
    public GlyphInstance light(int light) {
        ExtraMemoryOps.put2x16(slabPtr() + OFF_LIGHT, light);
        return this;
    }

    public GlyphInstance anchor(float x, float y, float z) {
        ExtraMemoryOps.putVector3f(slabPtr() + OFF_ANCHOR, x, y, z);
        return this;
    }

    public GlyphInstance placement(float offsetX, float offsetY, float sizeX, float sizeY, float shear) {
        long p = slabPtr() + OFF_PLACEMENT;
        MemoryUtil.memPutFloat(p, offsetX);
        MemoryUtil.memPutFloat(p + 4, offsetY);
        MemoryUtil.memPutFloat(p + 8, sizeX);
        MemoryUtil.memPutFloat(p + 12, sizeY);
        MemoryUtil.memPutFloat(p + 16, shear);
        return this;
    }

    public GlyphInstance uvRegion(float offU, float offV, float scaleU, float scaleV) {
        ExtraMemoryOps.putVector4f(slabPtr() + OFF_UV_REGION, offU, offV, scaleU, scaleV);
        return this;
    }

    public GlyphInstance depth(float depth) {
        MemoryUtil.memPutFloat(slabPtr() + OFF_DEPTH, depth);
        return this;
    }
}
