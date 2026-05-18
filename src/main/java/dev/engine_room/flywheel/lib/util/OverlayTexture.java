package dev.engine_room.flywheel.lib.util;

// 1.12.2 has no net.minecraft.client.renderer.texture.OverlayTexture; this shim mirrors the
// upstream NO_OVERLAY + pack helper so the overlay slot in instance layouts stays stable.

public final class OverlayTexture {
    public static final int NO_WHITE_U = 0;
    public static final int WHITE_OVERLAY_V = 10;
    public static final int NO_OVERLAY = pack(NO_WHITE_U, WHITE_OVERLAY_V);

    private OverlayTexture() {
    }

    public static int pack(int u, int v) {
        return u | (v << 16);
    }

    public static int u(int packed) {
        return packed & 0xFFFF;
    }

    public static int v(int packed) {
        return (packed >> 16) & 0xFFFF;
    }
}
