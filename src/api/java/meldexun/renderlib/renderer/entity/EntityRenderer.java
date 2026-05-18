package meldexun.renderlib.renderer.entity;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;

public class EntityRenderer {
    public void setup(ICamera frustum, float partialTicks, double camX, double camY, double camZ) {
        throw new AssertionError();
    }

    private boolean shouldRender(Entity entity, ICamera frustum, double partialTicks,
                                 double camX, double camY, double camZ) {
        throw new AssertionError();
    }
}
