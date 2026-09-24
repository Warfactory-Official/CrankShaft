package dev.engine_room.flywheel.impl.visualization;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.mixin.LivingEntityRendererInvoker;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.util.HeldItemPoses;
import dev.engine_room.flywheel.lib.visual.util.ItemStackSlot;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Held stacks whose item has an {@code ItemStackVisualizer}, on entities vanilla renders (players; every entity
 * without a visual): vanilla's extraction poses the hands, the stack's visual draws them, vanilla's hand items are
 * cleared. A host joins the effects on first sight and takes over once its visual exists: no frame draws both or
 * neither.
 */
public final class HeldItemHosts {
    private static final RendererReloadCache<Boolean, Map<LivingEntity, Host>> HOSTS = new RendererReloadCache<>(
            $ -> new ConcurrentHashMap<>());
    private static final int EXPIRY_FRAMES = 40;

    private HeldItemHosts() {
    }

    // ArmedEntityRenderState.extractArmedEntityRenderState TAIL, on extraction threads.
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void extract(LivingEntity entity, ArmedEntityRenderState state) {
        boolean right = ItemStackSlot.isVisualized(state.rightHandItemStack);
        boolean left = ItemStackSlot.isVisualized(state.leftHandItemStack);
        if (!right && !left) {
            return;
        }
        VisualizationManager manager = VisualizationManager.get(entity.level());
        // A visual's own capture runs this extraction too: its visual draws the hands.
        if (manager == null || VisualizationHelper.skipVanillaRender(entity)
                || !(Minecraft.getInstance().getEntityRenderDispatcher()
                              .getRenderer(entity) instanceof LivingEntityRenderer renderer)
                || !(renderer.getModel() instanceof ArmedModel)) {
            return;
        }
        Map<LivingEntity, Host> hosts = HOSTS.get(true);
        Host host = hosts.get(entity);
        if (host == null) {
            host = new Host(entity);
            if (hosts.putIfAbsent(entity, host) == null) {
                manager.effects().queueAdd(host);
            }
            return;
        }
        if (!host.live) {
            return;
        }
        host.published = pose(renderer, state, right, left);
        if (right) {
            state.rightHandItemState.clear();
        }
        if (left) {
            state.leftHandItemState.clear();
        }
    }

    // LivingEntityRenderer.submit up to its layers, then ItemInHandLayer; the entity's render position excluded.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Snapshot pose(LivingEntityRenderer renderer, ArmedEntityRenderState state, boolean right,
                                 boolean left) {
        LivingEntityRendererInvoker invoker = (LivingEntityRendererInvoker) renderer;
        if (!invoker.flywheel$shouldRenderLayers(state)) {
            return new Snapshot(state.x, state.y, state.z, null, ItemStack.EMPTY, null, ItemStack.EMPTY, 0);
        }
        PoseStack ps = new PoseStack();
        Vec3 offset = renderer.getRenderOffset(state);
        ps.translate(offset.x, offset.y, offset.z);
        Direction bed = state.bedOrientation;
        if (state.hasPose(Pose.SLEEPING) && bed != null) {
            float headOffset = state.eyeHeight - 0.1F;
            ps.translate(-bed.getStepX() * headOffset, 0.0F, -bed.getStepZ() * headOffset);
        }
        float scale = state.scale;
        ps.scale(scale, scale, scale);
        invoker.flywheel$setupRotations(state, ps, state.bodyRot, scale);
        ps.scale(-1.0F, -1.0F, 1.0F);
        invoker.flywheel$scale(state, ps);
        ps.translate(0.0F, -1.501F, 0.0F);
        EntityModel model = renderer.getModel();
        Matrix4f rightPose = null;
        Matrix4f leftPose = null;
        // Concurrent extraction shares the renderer's model.
        synchronized (model) {
            model.setupAnim(state);
            if (right) {
                rightPose = hand(ps, state, HumanoidArm.RIGHT, (ArmedModel) model, state.rightHandItemStack);
            }
            if (left) {
                leftPose = hand(ps, state, HumanoidArm.LEFT, (ArmedModel) model, state.leftHandItemStack);
            }
        }
        return new Snapshot(state.x, state.y, state.z, rightPose, state.rightHandItemStack, leftPose,
                state.leftHandItemStack, state.lightCoords);
    }

    @SuppressWarnings("rawtypes")
    private static Matrix4f hand(PoseStack ps, ArmedEntityRenderState state, HumanoidArm arm, ArmedModel model,
                                 ItemStack stack) {
        ps.pushPose();
        HeldItemPoses.thirdPersonHand(state, arm, model, stack, ps);
        Matrix4f pose = new Matrix4f(ps.last().pose());
        ps.popPose();
        return pose;
    }

    private record Snapshot(double x, double y, double z, @Nullable Matrix4f right, ItemStack rightStack,
                            @Nullable Matrix4f left, ItemStack leftStack, int light) {
    }

    private static final class Host implements Effect {
        final LivingEntity entity;
        volatile boolean live;
        volatile @Nullable Snapshot published;

        Host(LivingEntity entity) {
            this.entity = entity;
        }

        @Override
        public LevelAccessor level() {
            return entity.level();
        }

        @Override
        public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
            return new HostVisual(ctx, this, partialTick);
        }
    }

    private static final class HostVisual extends AbstractVisual implements EffectVisual<Host>, SimpleDynamicVisual {
        private final Host host;
        private final ItemStackSlot right = new ItemStackSlot();
        private final ItemStackSlot left = new ItemStackSlot();
        private final Matrix4f pose = new Matrix4f();
        private int misses;

        HostVisual(VisualizationContext ctx, Host host, float partialTick) {
            super(ctx, host.entity.level(), partialTick);
            this.host = host;
            host.live = true;
        }

        @Override
        public void beginFrame(DynamicVisual.Context ctx) {
            Snapshot snapshot = host.published;
            host.published = null;
            if (snapshot == null) {
                right.hide();
                left.hide();
                if (++misses > EXPIRY_FRAMES && host.live) {
                    host.live = false;
                    VisualizationManager manager = VisualizationManager.get(level);
                    if (manager != null) {
                        manager.effects().queueRemove(host);
                    }
                }
                return;
            }
            misses = 0;
            draw(right, snapshot.right(), snapshot.rightStack(), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, snapshot,
                    ctx.partialTick());
            draw(left, snapshot.left(), snapshot.leftStack(), ItemDisplayContext.THIRD_PERSON_LEFT_HAND, snapshot,
                    ctx.partialTick());
        }

        private void draw(ItemStackSlot slot, @Nullable Matrix4f local, ItemStack stack,
                          ItemDisplayContext displayContext, Snapshot snapshot, float partialTick) {
            if (local == null) {
                slot.hide();
                return;
            }
            Vec3i origin = renderOrigin();
            pose.translation((float) (snapshot.x() - origin.getX()), (float) (snapshot.y() - origin.getY()),
                        (float) (snapshot.z() - origin.getZ()))
                .mul(local);
            slot.draw(visualizationContext, stack, displayContext, host.entity, host.entity.getId(), pose,
                    snapshot.light(), OverlayTexture.NO_OVERLAY, partialTick);
        }

        @Override
        protected void _delete() {
            right.delete();
            left.delete();
            HOSTS.get(true).remove(host.entity, host);
        }
    }
}
