package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import org.joml.Matrix4f;

public class TntMinecartVisual<T extends MinecartTNT> extends MinecartVisual<T> {
    // 0.75 matches vanilla's overlay white-band intensity.
    private static final int WHITE_OVERLAY = OverlayTexture.whitePack(0.75F);

    public TntMinecartVisual(VisualizationContext ctx, T entity, float partialTick) {
        super(ctx, entity, partialTick, ModelLayers.TNT_MINECART);
    }

    @Override
    protected void updateContents(TransformedInstance contents, Matrix4f pose, float partialTick) {
        int fuseTime = entity.getFuse();
        float fuse = fuseTime > -1 ? fuseTime - partialTick + 1.0F : -1.0F;

        if (fuse > -1.0F && fuse < 10.0F) {
            float swell = TntRenderer.getSwellAmount(fuse);
            pose.translate(-swell * 0.5F, 0.0F, -swell * 0.5F);
            float scale = 1.0F + swell;
            pose.scale(scale);
        }

        int overlay = TntRenderer.isLit(fuse) ? WHITE_OVERLAY : OverlayTexture.NO_OVERLAY;

        contents.setTransform(pose)
                .overlay(overlay)
                .setChanged();
    }
}
