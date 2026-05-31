package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.item.ItemModels;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.IllagerEntityModel;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.entity.monster.AbstractIllager;
import net.minecraft.entity.monster.EntityIllusionIllager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Illusioner mirror bodies (copies 1-3; copy 0 is the visual's own body), each at {@code rootPose} pre-translated
 *  by {@code offset[i] - offset[0]}. Shown only while invisible; bow drawn on every copy, per vanilla. */
public final class IllusionerMirrorLayer implements LivingLayer {
    private static final int COPIES = 3;
    private static final int ARM_BONE = 5; // ModelIllager rightArm root index
    private static final TransformType ITEM_TRANSFORM = TransformType.THIRD_PERSON_RIGHT_HAND;

    private final EntityIllusionIllager illusioner;
    private final InstanceTree body;
    private final InstanceTree[] copies;
    private final InstancerProvider instancers;
    private final int itemBias;
    private final Matrix4f scratch = new Matrix4f();

    @Nullable
    private ItemStack itemStack;
    private final TransformedInstance[] items = new TransformedInstance[COPIES];

    private boolean parentVisible = true;
    private boolean shown = false;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;
    private boolean lastCrossed;

    public IllusionerMirrorLayer(VisualizationContext ctx, EntityIllusionIllager illusioner, InstanceTree body,
                                 Material material, int bias) {
        this.illusioner = illusioner;
        this.body = body;
        this.instancers = ctx.instancerProvider();
        this.itemBias = bias + 1;
        this.copies = new InstanceTree[COPIES];
        for (int i = 0; i < COPIES; i++) {
            copies[i] = InstanceTree.create(instancers,
                    AbstractLivingEntityVisual.buildTree(new IllagerEntityModel(), material, "illusioner:mirror"), bias);
            copies[i].visible(false);
        }
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        boolean show = parentVisible && illusioner.isInvisible();
        if (show != shown) {
            shown = show;
            for (InstanceTree copy : copies) {
                copy.visible(show);
            }
            if (show) {
                lastLight = Integer.MIN_VALUE;
                lastOverlay = -1;
            }
        }
        if (!show) {
            clearItems();
            return;
        }

        Vec3d o0 = IllusionerVisual.offset(illusioner, 0, partialTick);
        boolean crossed = illusioner.getArmPose() == AbstractIllager.IllagerArmPose.CROSSED;
        int overlay = OverlayTexture.forEntity(illusioner);
        // An arm-pose flip reveals skip-drawn arm bones whose slots reseed to light 0, so it must force the push.
        boolean pushLight = light != lastLight || overlay != lastOverlay || crossed != lastCrossed;
        if (pushLight) {
            lastLight = light;
            lastOverlay = overlay;
            lastCrossed = crossed;
        }

        for (int i = 0; i < COPIES; i++) {
            InstanceTree copy = copies[i];
            copy.copyPoseFrom(body);
            copy.child(4).skipDraw(!crossed);
            copy.child(ARM_BONE).skipDraw(crossed);
            copy.child(6).skipDraw(crossed);

            if (pushLight) {
                copy.traverse(inst -> {
                    inst.light(light);
                    inst.overlay(overlay);
                    inst.colorArgb(0xFFFFFFFF);
                });
            }

            Vec3d oi = IllusionerVisual.offset(illusioner, i + 1, partialTick);
            scratch.set(rootPose);
            scratch.translateLocal((float) (oi.x - o0.x), (float) (oi.y - o0.y), (float) (oi.z - o0.z));
            copy.updateInstances(scratch);
        }

        // Posed after the copies so each arm bone's world matrix is current.
        ItemStack stack = illusioner.getHeldItemMainhand();
        boolean showItem = (illusioner.isSpellcasting() || illusioner.isAggressive())
                && !stack.isEmpty() && ItemModels.isSupported(stack);
        if (!showItem) {
            clearItems();
            return;
        }
        if (itemStack == null || !ItemStack.areItemStacksEqual(stack, itemStack)) {
            clearItems();
            itemStack = stack.copy();
            Model model = ItemModels.get(illusioner.world, stack, ITEM_TRANSFORM);
            for (int i = 0; i < COPIES; i++) {
                items[i] = instancers.instancer(InstanceTypes.TRANSFORMED, model, itemBias).createInstance();
            }
        }
        for (int i = 0; i < COPIES; i++) {
            scratch.set(copies[i].child(ARM_BONE).poseMatrix());
            scratch.rotateX((float) (-Math.PI / 2.0));
            scratch.rotateY((float) Math.PI);
            scratch.translate(0.0625F, 0.125F, -0.625F);
            items[i].setTransform(scratch);
            items[i].light(light);
            items[i].overlay(OverlayTexture.NO_OVERLAY);
            items[i].colorArgb(0xFFFFFFFF);
            items[i].setChanged();
        }
    }

    private void clearItems() {
        if (itemStack == null) {
            return;
        }
        for (int i = 0; i < COPIES; i++) {
            if (items[i] != null) {
                items[i].delete();
                items[i] = null;
            }
        }
        itemStack = null;
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            for (InstanceTree copy : copies) {
                copy.visible(false);
            }
            clearItems();
        }
    }

    @Override
    public void delete() {
        for (InstanceTree copy : copies) {
            copy.delete();
        }
        clearItems();
    }
}
