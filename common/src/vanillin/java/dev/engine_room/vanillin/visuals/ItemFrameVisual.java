package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.NameTagComponent;
import dev.engine_room.flywheel.lib.visual.util.ItemStackSlot;
import dev.engine_room.vanillin.item.ItemModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BlockStateDefinitions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.Objects;


/**
 * Item-frame / glow-item-frame visual: frame border baked from the fake block state vanilla resolves
 * ({@code BlockStateDefinitions.getItemFrameFakeState}), the framed item via {@link ItemModels} at FIXED.
 * Filled maps and special-renderer items fall back to vanilla (see {@link #shouldSkipVanilla}).
 */
public class ItemFrameVisual extends AbstractEntityVisual<ItemFrame> implements SimpleDynamicVisual {
    private static final RendererReloadCache<Boolean, Model> FRAME_MODELS = new RendererReloadCache<>(
            glow -> Objects.requireNonNull(Models.displayBlock(BlockStateDefinitions.getItemFrameFakeState(glow, false),
                    ItemFrameRenderer.BLOCK_DISPLAY_CONTEXT, true)));

    private final boolean glow;

    private final TransformedInstance frame;
    private final NameTagComponent nameTag;
    private final Matrix4f base = new Matrix4f();
    @Nullable
    private TransformedInstance item;
    private final ItemStackSlot itemSlot = new ItemStackSlot();
    private boolean visualized;
    private ItemStack currentStack = ItemStack.EMPTY;

    public ItemFrameVisual(VisualizationContext ctx, ItemFrame entity, float partialTick) {
        super(ctx, entity, partialTick);
        glow = entity.getType() == EntityTypes.GLOW_ITEM_FRAME;

        frame = instancerProvider().instancer(InstanceTypes.TRANSFORMED, frameModel(glow))
                                   .createInstance();

        nameTag = new NameTagComponent(ctx, entity)
                .nameTag(() -> entity.getItem().getHoverName())
                .shouldShow(() -> !Minecraft.getInstance().gui.hud.isHidden()
                        && Minecraft.getInstance().getEntityRenderDispatcher().crosshairPickEntity == entity
                        && entity.getItem().getCustomName() != null);

        updateItem();
        animate(partialTick);
    }

    private static Model frameModel(boolean glow) {
        return FRAME_MODELS.get(glow);
    }

    public static boolean shouldSkipVanilla(ItemFrame entity) {
        ItemStack stack = entity.getItem();
        if (stack.is(Items.FILLED_MAP)) {
            return false;
        }
        if (stack.isEmpty() || ItemStackSlot.isVisualized(stack)) {
            return true;
        }
        return ItemModels.isSupported(stack, ItemDisplayContext.FIXED, entity, entity.getId());
    }

    private void updateItem() {
        currentStack = entity.getItem();
        visualized = ItemStackSlot.isVisualized(currentStack);
        if (visualized) {
            if (item != null) {
                item.delete();
                item = null;
            }
            return;
        }
        item = ItemModels.rebake(instancerProvider(), item, currentStack, ItemDisplayContext.FIXED, entity,
                entity.getId());
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        if (!isVisible(context.frustum())) {
            return;
        }
        if (!ItemStack.matches(entity.getItem(), currentStack)
                || ItemStackSlot.isVisualized(currentStack) != visualized) {
            updateItem();
        }
        animate(context.partialTick());
        if (shouldSkipVanilla(entity)) {
            nameTag.beginFrame(context);
        } else {
            nameTag.delete();
        }
    }

    private void animate(float partialTick) {
        boolean draw = shouldSkipVanilla(entity);
        boolean invisible = entity.isInvisible();

        frame.setVisible(draw && !invisible);
        if (item != null) {
            item.setVisible(draw);
        }
        if (!draw) {
            itemSlot.hide();
            return;
        }

        Direction dir = entity.getDirection();
        var origin = renderOrigin();
        Vec3 pos = entity.position();

        BlockPos lightPos = entity.blockPosition();
        int block = entity.isOnFire() ? 15 : level.getBrightness(LightLayer.BLOCK, lightPos);
        if (glow) {
            block = Math.max(5, block);
        }
        int sky = level.getBrightness(LightLayer.SKY, lightPos);
        int frameLight = LightCoordsUtil.pack(block, sky);
        int itemLight = glow ? LightCoordsUtil.pack(15, 15) : frameLight;

        // Net vanilla placement: the dispatcher's (entityPos + getRenderOffset) and ItemFrameRenderer.submit's -getRenderOffset cancel, so the frame origin is simply entityPos + dir*0.46875 toward the wall (as in upstream).
        base.translation(
                (float) (pos.x - origin.getX()) + dir.getStepX() * 0.46875f,
                (float) (pos.y - origin.getY()) + dir.getStepY() * 0.46875f,
                (float) (pos.z - origin.getZ()) + dir.getStepZ() * 0.46875f);
        float xRot;
        float yRot;
        if (dir.getAxis().isHorizontal()) {
            xRot = 0.0f;
            yRot = 180.0f - dir.toYRot();
        } else {
            xRot = -90.0f * dir.getAxisDirection().getStep();
            yRot = 180.0f;
        }
        base.rotateX(xRot * Mth.DEG_TO_RAD);
        base.rotateY(yRot * Mth.DEG_TO_RAD);

        frame.setTransform(new Matrix4f(base).translate(-0.5f, -0.5f, -0.5f))
             .light(frameLight)
             .setChanged();

        Matrix4f itemPose = new Matrix4f(base)
                .translate(0.0f, 0.0f, invisible ? 0.5f : 0.4375f)
                .rotateZ(entity.getRotation() * (360.0f / 8.0f) * Mth.DEG_TO_RAD)
                .scale(0.5f);
        if (item != null) {
            item.setTransform(itemPose)
                .light(itemLight)
                .setChanged();
        }
        itemSlot.draw(visualizationContext, currentStack, ItemDisplayContext.FIXED, entity, entity.getId(), itemPose,
                itemLight, OverlayTexture.NO_OVERLAY, partialTick);
    }

    @Override
    protected void _delete() {
        frame.delete();
        if (item != null) {
            item.delete();
        }
        itemSlot.delete();
        nameTag.delete();
    }
}
