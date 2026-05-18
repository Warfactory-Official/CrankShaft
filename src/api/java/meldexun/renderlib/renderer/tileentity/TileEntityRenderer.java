package meldexun.renderlib.renderer.tileentity;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.tileentity.TileEntity;

public class TileEntityRenderer {
    public void setup(ICamera frustum, float partialTicks, double camX, double camY, double camZ) {
        throw new AssertionError();
    }

    private boolean shouldRender(TileEntity tileEntity, ICamera frustum, float partialTicks,
                                 double camX, double camY, double camZ) {
        throw new AssertionError();
    }
}
