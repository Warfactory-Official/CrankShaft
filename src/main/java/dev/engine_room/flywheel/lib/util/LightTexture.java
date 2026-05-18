package dev.engine_room.flywheel.lib.util;

/**
 * CrankShaft shim: 1.12.2 has no {@code net.minecraft.client.renderer.texture.LightTexture}
 * (1.16+ only). Mirrors upstream's packed-lightmap constant — block-light 15 in the low byte,
 * sky-light 15 in the high byte ({@code 0xF000F0}). Used by visuals that bypass world lighting
 * (debug overlays, fire, shadows, line meshes, the light-storage debug visual).
 */
public final class LightTexture {
    public static final int FULL_BRIGHT = 0xF000F0;
    public static final int FULL_SKY = 0xF00000;
    public static final int FULL_BLOCK = 0xF0;

    private LightTexture() {
    }

    public static int pack(int blockLight, int skyLight) {
        return blockLight << 4 | skyLight << 20;
    }

    public static int block(int packedLight) {
        return packedLight >> 4 & 0xFFFF;
    }

    public static int sky(int packedLight) {
        return packedLight >> 20 & 0xFFFF;
    }
}
