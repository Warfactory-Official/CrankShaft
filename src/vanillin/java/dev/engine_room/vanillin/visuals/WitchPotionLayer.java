package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.item.ItemModels;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelWitch;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumBlockRenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Witch potion held item, anchored to the nose bone (vanilla {@code LayerHeldItemWitch} uses {@code villagerNose.postRender}). */
public final class WitchPotionLayer implements LivingLayer {
    private static final TransformType TRANSFORM = TransformType.THIRD_PERSON_RIGHT_HAND;
    private static final float MODEL_SCALE = 0.0625F;
    private static final float ROT_Z_M20 = (float) Math.toRadians(-20.0);
    private static final float ROT_X_M60 = (float) Math.toRadians(-60.0);
    private static final float ROT_Z_M30 = (float) Math.toRadians(-30.0);
    private static final float ROT_X_M15 = (float) Math.toRadians(-15.0);
    private static final float ROT_Z_40 = (float) Math.toRadians(40.0);
    private static final float ROT_X_30 = (float) Math.toRadians(30.0);
    private static final float ROT_Y_M5 = (float) Math.toRadians(-5.0);
    private static final float ROT_Y_M45 = (float) Math.toRadians(-45.0);
    private static final float ROT_X_M100 = (float) Math.toRadians(-100.0);
    private static final float ROT_Y_M20 = (float) Math.toRadians(-20.0);
    private static final float ROT_Z_180 = (float) Math.toRadians(180.0);

    private final InstancerProvider instancers;
    private final EntityWitch witch;
    private final ModelRenderer nose;
    private final int bias;
    private final Matrix4f scratch = new Matrix4f();

    @Nullable
    private ItemStack currentStack;
    @Nullable
    private TransformedInstance instance;
    private boolean parentVisible = true;

    public WitchPotionLayer(VisualizationContext ctx, EntityWitch witch, ModelWitch model, int bias) {
        this.instancers = ctx.instancerProvider();
        this.witch = witch;
        this.nose = model.villagerNose;
        this.bias = bias;
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        if (!parentVisible) {
            clear();
            return;
        }
        ItemStack stack = witch.getHeldItemMainhand();
        if (stack.isEmpty() || !ItemModels.isSupported(stack)) {
            clear();
            return;
        }

        if (currentStack == null || !ItemStack.areItemStacksEqual(stack, currentStack)) {
            if (instance != null) {
                instance.delete();
            }
            currentStack = stack.copy();
            Model model = ItemModels.get(witch.world, stack, TRANSFORM);
            instance = instancers.instancer(InstanceTypes.TRANSFORMED, model, bias).createInstance();
        }

        // Reconstruct villagerNose.postRender on the bare root pose, NOT the world matrix: that folds in head
        // look-yaw/pitch and the drinking offset postRender skips. Then the LayerHeldItemWitch offset + item branch.
        scratch.set(rootPose);
        scratch.translate(nose.rotationPointX * MODEL_SCALE, nose.rotationPointY * MODEL_SCALE, nose.rotationPointZ * MODEL_SCALE);
        scratch.rotateZ(nose.rotateAngleZ);
        scratch.rotateY(nose.rotateAngleY);
        scratch.rotateX(nose.rotateAngleX);
        scratch.translate(-0.0625F, 0.53125F, 0.21875F);

        // LayerHeldItemWitch.doRenderLayer item branch (vanilla lines 57-97).
        Item item = stack.getItem();
        if (Block.getBlockFromItem(item).getDefaultState().getRenderType() == EnumBlockRenderType.ENTITYBLOCK_ANIMATED) {
            scratch.translate(0.0F, 0.0625F, -0.25F);
            scratch.rotateX(ROT_X_30);
            scratch.rotateY(ROT_Y_M5);
            scratch.scale(0.375F, -0.375F, 0.375F);
        } else if (item instanceof ItemBow) {
            scratch.translate(0.0F, 0.125F, -0.125F);
            scratch.rotateY(ROT_Y_M45);
            scratch.scale(0.625F, -0.625F, 0.625F);
            scratch.rotateX(ROT_X_M100);
            scratch.rotateY(ROT_Y_M20);
        } else if (item.isFull3D()) {
            if (item.shouldRotateAroundWhenRendering()) {
                scratch.rotateZ(ROT_Z_180);
                scratch.translate(0.0F, -0.0625F, 0.0F);
            }
            // RenderWitch.transformHeldFull3DItemLayer overrides the RenderLivingBase no-op.
            scratch.translate(0.0F, 0.1875F, 0.0F);
            scratch.translate(0.0625F, -0.125F, 0.0F);
            scratch.scale(0.625F, -0.625F, 0.625F);
        } else {
            scratch.translate(0.1875F, 0.1875F, 0.0F);
            scratch.scale(0.875F, 0.875F, 0.875F);
            scratch.rotateZ(ROT_Z_M20);
            scratch.rotateX(ROT_X_M60);
            scratch.rotateZ(ROT_Z_M30);
        }

        scratch.rotateX(ROT_X_M15);
        scratch.rotateZ(ROT_Z_40);

        instance.setTransform(scratch);
        instance.light(light);
        instance.overlay(OverlayTexture.NO_OVERLAY);
        instance.colorArgb(0xFFFFFFFF);
        instance.setChanged();
    }

    private void clear() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
        currentStack = null;
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible) {
            clear();
        }
    }

    @Override
    public void delete() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
    }
}
