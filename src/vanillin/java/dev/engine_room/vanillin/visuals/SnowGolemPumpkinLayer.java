package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.item.ItemModels;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Snow golem's carved-pumpkin head item on the head bone (vanilla {@code LayerSnowmanHead}); hidden when sheared. */
public final class SnowGolemPumpkinLayer implements LivingLayer {
    // head root index in SnowGolemEntityModel.
    private static final int HEAD_BONE = 2;

    private final EntitySnowman snowman;
    private final InstanceTree body;
    private final TransformedInstance pumpkin;
    private final Matrix4f scratch = new Matrix4f();

    private boolean parentVisible = true;
    private boolean shown;

    public SnowGolemPumpkinLayer(VisualizationContext ctx, EntitySnowman snowman, InstanceTree body, int bias) {
        this.snowman = snowman;
        this.body = body;
        InstancerProvider provider = ctx.instancerProvider();
        Model model = ItemModels.get(snowman.world, new ItemStack(Blocks.PUMPKIN, 1), TransformType.HEAD);
        this.pumpkin = provider.instancer(InstanceTypes.TRANSFORMED, model, bias).createInstance();
        this.pumpkin.setVisible(false);
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && snowman.isPumpkinEquipped();
        if (show != shown) {
            shown = show;
            pumpkin.setVisible(show);
        }
        if (!show) {
            return;
        }

        scratch.set(body.child(HEAD_BONE).poseMatrix());
        scratch.translate(0.0F, -0.34375F, 0.0F);
        scratch.rotateY((float) Math.PI);
        scratch.scale(0.625F, -0.625F, -0.625F);

        pumpkin.setTransform(scratch);
        pumpkin.light(light);
        // ItemModels material is useOverlay(false): pumpkin can't red-flash, unlike vanilla. Accepted.
        pumpkin.colorArgb(0xFFFFFFFF);
        pumpkin.setChanged();
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            pumpkin.setVisible(false);
        }
    }

    @Override
    public void delete() {
        pumpkin.delete();
    }
}
