package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import org.lwjgl.system.MemoryUtil;

/** One nameplate element (glyph quad, style rect, or background box) billboarded about the shared
 *  label {@code anchor}: {@code offset}/{@code size} place the unit quad in font pixels (y down,
 *  0.025 world units per px), {@code shear} leans italics. The seed default {@code size} 0 collapses
 *  unposed slots to a point. */
public class GlyphInstance extends AbstractInstance implements FlatLit {
    static final int OFF_RGBA = 0;
    static final int OFF_LIGHT = 4;
    static final int OFF_ANCHOR = 8;
    static final int OFF_PLACEMENT = 20;
    static final int OFF_UV_REGION = 40;

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
        long p = slabPtr() + OFF_ANCHOR;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
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
        long p = slabPtr() + OFF_UV_REGION;
        MemoryUtil.memPutFloat(p, offU);
        MemoryUtil.memPutFloat(p + 4, offV);
        MemoryUtil.memPutFloat(p + 8, scaleU);
        MemoryUtil.memPutFloat(p + 12, scaleV);
        return this;
    }
}
