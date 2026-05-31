package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.client.model.ModelIronGolem;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.init.Blocks;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Iron golem's poppy ({@code RED_FLOWER} block model), held while {@code getHoldRoseTick() != 0}, per vanilla
 *  {@code LayerIronGolemFlower}. The terminal {@code rotateY(90°)} matches {@code renderModelBrightness}'s block-quad
 *  rotation; the scale's Y flip is load-bearing. */
public final class IronGolemRoseLayer implements LivingLayer {
    private final EntityIronGolem golem;
    private final ModelIronGolem scratchModel;
    private final TransformedInstance flower;
    private final Matrix4f scratch = new Matrix4f();

    private boolean parentVisible = true;
    private boolean shown;
    private int lastLight = Integer.MIN_VALUE;

    public IronGolemRoseLayer(VisualizationContext ctx, EntityIronGolem golem, ModelIronGolem scratchModel, int bias) {
        this.golem = golem;
        this.scratchModel = scratchModel;
        InstancerProvider provider = ctx.instancerProvider();
        Model model = EntityMaterials.BLOCK_OVERLAY_MODELS.get(Blocks.RED_FLOWER.getDefaultState());
        this.flower = provider.instancer(InstanceTypes.TRANSFORMED, model, bias).createInstance();
        this.flower.setVisible(false);
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && golem.getHoldRoseTick() != 0;
        if (show != shown) {
            shown = show;
            flower.setVisible(show);
            if (show) {
                // Reveal reseeds the freed slab slot; force the guarded push below.
                lastLight = Integer.MIN_VALUE;
            }
        }
        if (!show) {
            return;
        }

        // Overlay is constant NO_OVERLAY and color constant, so the only per-frame state delta is light.
        if (!bodyMoved && light == lastLight) {
            return;
        }
        lastLight = light;

        // Pitch follows the right arm, just posed onto the scratch model by the body's poseModel this frame.
        float armDeg = 5.0F + 180.0F * scratchModel.ironGolemRightArm.rotateAngleX / (float) Math.PI;
        scratch.set(rootPose)
                .rotateX((float) Math.toRadians(armDeg))
                .rotateX((float) Math.toRadians(90.0))
                .translate(-0.9375F, -0.625F, -0.9375F)
                .scale(0.5F, -0.5F, 0.5F)
                .rotateY((float) Math.toRadians(90.0));

        flower.setTransform(scratch);
        flower.light(light);
        // shouldCombineTextures() == false: the poppy never red-flashes.
        flower.overlay(OverlayTexture.NO_OVERLAY);
        flower.colorArgb(0xFFFFFFFF);
        flower.setChanged();
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            flower.setVisible(false);
        }
    }

    @Override
    public void delete() {
        flower.delete();
    }
}
