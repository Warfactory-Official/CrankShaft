package dev.engine_room.vanillin.oit;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.PackIdentity;
import dev.engine_room.flywheel.lib.model.PackTaggedModel;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.block.state.IBlockState;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Consumer;

public final class OitDemoVisual extends AbstractBlockEntityVisual<TileEntityOitDemo>
        implements ShaderLightVisual, SimpleDynamicVisual {
    private final OitDemoBlockAccess blockAccess;
    private IBlockState renderedState;
    private TransformedInstance instance;
    private int cullMask;
    private int packedLight;

    public OitDemoVisual(VisualizationContext ctx, TileEntityOitDemo te, float partialTick) {
        super(ctx, te, partialTick);

        renderedState = OitDemoBlockAccess.renderedState(blockState);
        blockAccess = new OitDemoBlockAccess(level);
        cullMask = blockAccess.cullMask(renderedState, pos);
        packedLight = computePackedLight();
        instance = createInstance();
        writePose();
    }

    private TransformedInstance createInstance() {
        var result = instancerProvider()
                // The glass block it stands in for, under a shaderpack: its ids, and terrain's shadow casting.
                .instancer(InstanceTypes.TRANSFORMED, new PackTaggedModel(Models.block(renderedState, cullMask),
                        List.of(PackIdentity.of(renderedState))))
                .createInstance();
        result.overlay(OverlayTexture.NO_OVERLAY);
        return result;
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        // 1.12.2: metadata changes retain the tile entity.
        var newState = OitDemoBlockAccess.renderedState(level.getBlockState(pos));
        var newMask = blockAccess.cullMask(newState, pos);
        if (newState == renderedState && newMask == cullMask) return;
        renderedState = newState;
        cullMask = newMask;
        instance.delete();
        instance = createInstance();
        writePose();
    }

    private void writePose() {
        instance.setTransform(new Matrix4f().translate(visualPos.getX(), visualPos.getY(), visualPos.getZ()))
                .light(packedLight)
                .setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        packedLight = computePackedLight();
        instance.light(packedLight).setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(instance);
    }
}
