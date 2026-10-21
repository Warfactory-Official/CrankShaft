package net.optifine.shaders;

import net.minecraft.client.renderer.culling.ClippingHelper;

public class ClippingHelperShadow extends ClippingHelper {
    float[][] shadowClipPlanes;
    int shadowClipPlaneCount;
    public static ClippingHelper getInstance() {
        throw new UnsupportedOperationException();
    }
}
