package dev.engine_room.flywheel.lib.util;

import net.minecraft.entity.EntityLivingBase;

import java.util.Arrays;

// 1.12.2 has no net.minecraft.client.renderer.texture.OverlayTexture. This shim provides the packed
// (u, v) coords and generates the 16x16 overlay texture the backend binds to flw_overlayTex (T1),
// reproducing vanilla RenderLivingBase: a 30% lerp toward red for hurt/death, plus a white-flash ramp.

public final class OverlayTexture {
    public static final int NO_WHITE_U = 0;
    public static final int WHITE_OVERLAY_V = 10;
    public static final int NO_OVERLAY = pack(NO_WHITE_U, WHITE_OVERLAY_V);

    public static final int HURT_U = 3;
    public static final int HURT = pack(HURT_U, WHITE_OVERLAY_V);

    // White-flash ramp lives in rows [0, WHITE_MAX_V] at u = NO_WHITE_U (v=0 full white, v=MAX none).
    public static final int WHITE_MAX_V = 7;

    private OverlayTexture() {
    }

    public static int forEntity(EntityLivingBase entity) {
        return (entity.hurtTime > 0 || entity.deathTime > 0) ? HURT : NO_OVERLAY;
    }

    /** Overlay coord for a white flash of the given intensity (0 = none, 1 = full white). */
    public static int whitePack(float intensity) {
        int v = Math.round((1.0F - intensity) * WHITE_MAX_V);
        if (v < 0) {
            v = 0;
        } else if (v > WHITE_MAX_V) {
            v = WHITE_MAX_V;
        }
        return pack(NO_WHITE_U, v);
    }

    /** Fill a 16x16 ARGB buffer. Alpha 1.0 (mix keeps the original color) everywhere except: the hurt
     *  texel — red at 0.70 alpha (30% red lerp); and the white-flash ramp at u = NO_WHITE_U. */
    public static void fillTextureData(int[] data) {
        Arrays.fill(data, 0xFF000000);
        data[WHITE_OVERLAY_V * 16 + HURT_U] = 0xB3FF0000;
        for (int v = 0; v <= WHITE_MAX_V; v++) {
            int alpha = Math.round((float) v / WHITE_MAX_V * 255.0F);
            data[v * 16 + NO_WHITE_U] = (alpha << 24) | 0x00FFFFFF;
        }
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
