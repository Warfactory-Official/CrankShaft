package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.item.ItemModels;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * A {@link LivingLayer} rendering an entity's held item(s) as instanced models on biped arm bones. Two constructor
 * modes: main-hand-only on one arm bone (primary-hand-agnostic), or both hands with the vanilla {@code LayerHeldItem}
 * primary-hand swap. An arm bone index is its position in {@link EntityModel#roots} (2/3 = {@code bipedRightArm}/
 * {@code bipedLeftArm}). Empty hands and built-in-renderer items (shields, banners, …) fall back to vanilla.
 */
public final class HeldItemLayer implements LivingLayer {
    private static final TransformType[] TRANSFORM = { TransformType.THIRD_PERSON_RIGHT_HAND, TransformType.THIRD_PERSON_LEFT_HAND };
    private static final float[] HAND_SIGN = { 1.0F, -1.0F };

    private final InstancerProvider instancers;
    private final EntityLivingBase entity;
    private final InstanceTree body;
    private final int rightArmBone;
    // -1 ⇒ main-hand-only (right slot, primary-hand-agnostic).
    private final int leftArmBone;
    private final int bias;
    @Nullable
    private final Predicate<EntityLivingBase> visibleWhen;
    private final Matrix4f scratch = new Matrix4f();

    private final ItemStack[] currentStack = { null, null };
    private final TransformedInstance[] instance = { null, null };
    private boolean visible = true;

    public HeldItemLayer(VisualizationContext ctx, EntityLivingBase entity, InstanceTree body, int armBoneIndex, int bias) {
        this(ctx, entity, body, armBoneIndex, -1, bias, null);
    }

    public HeldItemLayer(VisualizationContext ctx, EntityLivingBase entity, InstanceTree body, int armBoneIndex, int bias,
                         @Nullable Predicate<EntityLivingBase> visibleWhen) {
        this(ctx, entity, body, armBoneIndex, -1, bias, visibleWhen);
    }

    public HeldItemLayer(VisualizationContext ctx, EntityLivingBase entity, InstanceTree body,
                         int rightArmBoneIndex, int leftArmBoneIndex, int bias) {
        this(ctx, entity, body, rightArmBoneIndex, leftArmBoneIndex, bias, null);
    }

    private HeldItemLayer(VisualizationContext ctx, EntityLivingBase entity, InstanceTree body,
                          int rightArmBoneIndex, int leftArmBoneIndex, int bias, @Nullable Predicate<EntityLivingBase> visibleWhen) {
        this.instancers = ctx.instancerProvider();
        this.entity = entity;
        this.body = body;
        this.rightArmBone = rightArmBoneIndex;
        this.leftArmBone = leftArmBoneIndex;
        this.bias = bias;
        this.visibleWhen = visibleWhen;
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        if (!visible || (visibleWhen != null && !visibleWhen.test(entity))) {
            clear(0);
            clear(1);
            return;
        }

        ItemStack rightStack;
        ItemStack leftStack;
        if (leftArmBone < 0) {
            rightStack = entity.getHeldItemMainhand();
            leftStack = ItemStack.EMPTY;
        } else {
            // Vanilla LayerHeldItem: the right slot shows the main hand iff the primary hand is the right.
            boolean primaryRight = entity.getPrimaryHand() == EnumHandSide.RIGHT;
            rightStack = primaryRight ? entity.getHeldItemMainhand() : entity.getHeldItemOffhand();
            leftStack = primaryRight ? entity.getHeldItemOffhand() : entity.getHeldItemMainhand();
        }

        poseHand(0, rightArmBone, rightStack, light);
        if (leftArmBone >= 0) {
            poseHand(1, leftArmBone, leftStack, light);
        }
    }

    private void poseHand(int slot, int armBone, ItemStack stack, int light) {
        if (stack.isEmpty() || !ItemModels.isSupported(stack)) {
            clear(slot);
            return;
        }

        // Re-bake only when the stack changes; the entity's stack is mutated in place, so cache a copy.
        if (currentStack[slot] == null || !ItemStack.areItemStacksEqual(stack, currentStack[slot])) {
            if (instance[slot] != null) {
                instance[slot].delete();
            }
            currentStack[slot] = stack.copy();
            Model model = ItemModels.get(entity.world, stack, TRANSFORM[slot]);
            instance[slot] = instancers.instancer(InstanceTypes.TRANSFORMED, model, bias).createInstance();
        }

        // Arm pose is already in world units (0.0625 scale baked in), so the vanilla LayerHeldItem hand offset
        // constants apply directly — no extra /16. The sneak 0.2 drop rides the arm bone (BipedLivingEntityVisual
        // drops the body root), so unlike vanilla LayerHeldItem we re-apply nothing here.
        scratch.set(body.child(armBone).poseMatrix());
        scratch.rotateX((float) (-Math.PI / 2.0));
        scratch.rotateY((float) Math.PI);
        scratch.translate(HAND_SIGN[slot] * 0.0625F, 0.125F, -0.625F);

        instance[slot].setTransform(scratch);
        // Drive light every frame: a reveal reseeds the slab slot with light 0 (pitch black).
        instance[slot].light(light);
        instance[slot].overlay(OverlayTexture.NO_OVERLAY);
        instance[slot].colorArgb(0xFFFFFFFF);
        instance[slot].setChanged();
    }

    private void clear(int slot) {
        if (instance[slot] != null) {
            instance[slot].delete();
            instance[slot] = null;
        }
        currentStack[slot] = null;
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            clear(0);
            clear(1);
        }
    }

    @Override
    public void delete() {
        for (int slot = 0; slot < instance.length; slot++) {
            if (instance[slot] != null) {
                instance[slot].delete();
                instance[slot] = null;
            }
        }
    }
}
