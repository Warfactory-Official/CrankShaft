package dev.engine_room.flywheel.backend.engine.terrain;

import java.util.Objects;

import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;

public final class TerrainAtlasFilter {
    // Injected into a terrain fragment's preamble (absent when crisp); gates flw_sampleAtlas in texel_filter.glsl.
    public static final String LINEAR_DEFINE = "FLW_PIXEL_FILTER_LINEAR";

    private static final boolean SODIUM_LOADED = detectSodium();

    private TerrainAtlasFilter() {
    }

    public static boolean linear() {
        return !GuestTerrainGate.ownsTerrain() && SODIUM_LOADED
                && SodiumClientMod.options().quality.pixelFilteringMode == FilterMode.LINEAR;
    }

    // Iris's MixinDefaultChunkRenderer.iris$forceNearest substitutes clamp-to-edge NEAREST into
    // ShaderChunkRenderer.begin, which discards the argument; Sodium 0.9.2 draws with the sampler the caller passed.
    // A guest terrain draw therefore uses the same one every other path does.
    public static GpuSampler sampler() {
        return Objects.requireNonNull(Minecraft.getInstance().levelRenderer.chunkLayerSampler);
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
