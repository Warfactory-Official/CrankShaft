package toni.sodiumdynamiclights;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

public class SodiumDynamicLights {
    public Set<DynamicLightSource> dynamicLightSources;

    public static SodiumDynamicLights get() {
        throw new AssertionError();
    }

    public double getDynamicLightLevel(BlockPos pos) {
        throw new AssertionError();
    }

    public int getLightmapWithDynamicLight(BlockPos pos, int packedLight) {
        throw new AssertionError();
    }

    public int getLightmapWithDynamicLight(double dynamicLightLevel, int lightmap) {
        throw new AssertionError();
    }
}
