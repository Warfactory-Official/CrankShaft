package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

public class FallingBlockVisual extends AbstractEntityVisual<FallingBlockEntity> implements SimpleDynamicVisual {
    private final BlockState blockState;
    private final TransformedInstance instance;
    private final ShadowComponent shadowComponent;
    private final Matrix4f pose = new Matrix4f();

    public FallingBlockVisual(VisualizationContext ctx, FallingBlockEntity entity, float partialTick) {
        super(ctx, entity, partialTick);
        blockState = entity.getBlockState();
        instance = instancerProvider().instancer(InstanceTypes.TRANSFORMED,
                                              Models.block(blockState, blockState.getSeed(entity.getStartPos())))
                                      .createInstance();
        shadowComponent = new ShadowComponent(ctx, entity);
        animate(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        boolean draw = blockState.getRenderShape() == RenderShape.MODEL
                && blockState != level.getBlockState(entity.blockPosition());
        instance.setVisible(draw);
        if (!draw) {
            shadowComponent.radius(0.0f);
            shadowComponent.beginFrame(ctx);
            return;
        }
        animate(ctx.partialTick());
        shadowComponent.radius(0.5f);
        shadowComponent.strength((float) (1.0 - entity.distanceToSqr(ctx.camera().position()) / 256.0));
        shadowComponent.beginFrame(ctx);
    }

    private void animate(float partialTick) {
        var origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ());
        pose.translation(x, y, z)
            .translate(-0.5f, 0.0f, -0.5f);
        instance.setTransform(pose)
                .light(computePackedLight(partialTick))
                .setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
        shadowComponent.delete();
    }
}
