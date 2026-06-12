package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.textures.FilterMode;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;

public final class TerrainAtlasFilter {
    // Injected into a terrain fragment's preamble (absent when crisp); gates flw_sampleAtlas in texel_filter.glsl.
    public static final String LINEAR_DEFINE = "FLW_PIXEL_FILTER_LINEAR";

    private static final boolean SODIUM_LOADED = detectSodium();

    private TerrainAtlasFilter() {
    }

    public static boolean linear() {
        return SODIUM_LOADED && SodiumClientMod.options().quality.pixelFilteringMode == FilterMode.LINEAR;
    }

    private static boolean detectSodium() {
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
