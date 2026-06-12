package dev.engine_room.vanillin.visuals;

import com.mojang.math.Axis;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

public class PrimedTntVisual extends AbstractEntityVisual<PrimedTnt> implements SimpleDynamicVisual {
    private static final int WHITE_OVERLAY = OverlayTexture.pack(OverlayTexture.u(1.0F), 10);

    private final TransformedInstance instance;
    private final ShadowComponent shadowComponent;
    private final Matrix4f pose = new Matrix4f();

    public PrimedTntVisual(VisualizationContext ctx, PrimedTnt entity, float partialTick) {
        super(ctx, entity, partialTick);
        BlockState blockState = entity.getBlockState();
        instance = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.block(blockState))
                                      .createInstance();
        shadowComponent = new ShadowComponent(ctx, entity);
        animate(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        animate(ctx.partialTick());
        shadowComponent.radius(0.5f);
        shadowComponent.strength((float) (1.0 - entity.distanceToSqr(ctx.camera().position()) / 256.0));
        shadowComponent.beginFrame(ctx);
    }

    private void animate(float partialTick) {
        float fuse = entity.getFuse() - partialTick + 1.0F;
        var origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ());

        pose.translation(x, y, z)
            .translate(0.0f, 0.5f, 0.0f);
        if (fuse < 10.0F) {
            float scale = 1.0F + TntRenderer.getSwellAmount(fuse);
            pose.scale(scale, scale, scale);
        }
        pose.rotate(Axis.YP.rotationDegrees(-90.0F))
            .translate(-0.5f, -0.5f, 0.5f)
            .rotate(Axis.YP.rotationDegrees(90.0F));

        instance.setTransform(pose)
                .overlay(TntRenderer.isLit(fuse) ? WHITE_OVERLAY : OverlayTexture.NO_OVERLAY)
                .light(computePackedLight(partialTick))
                .setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
        shadowComponent.delete();
    }
}
