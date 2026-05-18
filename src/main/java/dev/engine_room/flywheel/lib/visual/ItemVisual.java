package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.compat.animation.SmartAnimatedTextureCompat;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.item.ItemModels;
import dev.engine_room.flywheel.lib.visual.component.FireComponent;
import dev.engine_room.flywheel.lib.visual.component.HitboxComponent;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import dev.engine_room.flywheel.lib.visual.util.InstanceRecycler;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Random;

public class ItemVisual extends ComponentEntityVisual<EntityItem> {

    private static final ThreadLocal<Random> RANDOM = ThreadLocal.withInitial(Random::new);

    private final Matrix4f scratchPose = new Matrix4f();
    private ItemStack currentStack;
    private boolean currentIsGui3d;
    private float currentGroundScaleY;
    private InstanceRecycler<TransformedInstance> instances;
    private Model currentModel;

    public ItemVisual(VisualizationContext ctx, EntityItem entity, float partialTick) {
        super(ctx, entity, partialTick);
        currentStack = entity.getItem();
        captureBakedProperties(currentStack);
        currentModel = ItemModels.get(level, currentStack, ItemCameraTransforms.TransformType.GROUND);
        Model model = currentModel;
        instances = new InstanceRecycler<>(() -> ctx.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, model)
                .createInstance());
        addComponent(new HitboxComponent(ctx, entity));
        addComponent(new FireComponent(ctx, entity));
        addComponent(new ShadowComponent(ctx, entity).radius(0.15F).strength(0.75F));
        animate(partialTick);
    }

    public static boolean isSupported(EntityItem entity) {
        return ItemModels.isSupported(entity.getItem());
    }

    @Override
    public void beginFrame(Context ctx) {
        super.beginFrame(ctx);
        if (!isVisible(ctx.frustum())) {
            return;
        }
        SmartAnimatedTextureCompat.touch(currentModel);
        animate(ctx.partialTick());
    }

    private void animate(float partialTick) {
        ItemStack itemstack = entity.getItem();
        if (itemstack.isEmpty()) {
            instances.resetCount();
            instances.discardExtra();
            return;
        }
        if (!ItemStack.areItemStacksEqual(itemstack, currentStack)) {
            instances.delete();
            currentStack = itemstack.copy();
            captureBakedProperties(currentStack);
            currentModel = ItemModels.get(level, currentStack, ItemCameraTransforms.TransformType.GROUND);
            Model model = currentModel;
            instances = new InstanceRecycler<>(() -> visualizationContext.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, model)
                    .createInstance());
        }
        instances.resetCount();

        float f3 = ((float) entity.getAge() + partialTick) / 10.0F + entity.hoverStart;
        float bob = MathHelper.sin(f3) * 0.1F + 0.1F;
        float spinDeg = (((float) entity.getAge() + partialTick) / 20.0F + entity.hoverStart) * (180F / (float) Math.PI);

        int renderCount = getModelCount(itemstack);
        boolean flag = currentIsGui3d;
        float groundScaleY = currentGroundScaleY;

        Vector3f entityPos = getVisualPosition(partialTick);

        int seed = itemstack.isEmpty() ? 187 : Item.getIdFromItem(itemstack.getItem()) + itemstack.getMetadata();
        Random random = RANDOM.get();
        random.setSeed(seed);

        int light = computePackedLight(partialTick);

        for (int k = 0; k < renderCount; ++k) {
            scratchPose.translation(entityPos.x, entityPos.y + bob + 0.25F * groundScaleY, entityPos.z);
            scratchPose.rotateY((float) Math.toRadians(spinDeg));

            if (!flag) {
                // No groundScaleY factor on the stride — it would halve the spacing for typical
                // flat items (ground.scale.y ≈ 0.5) and cause Z-fighting between stacked planes.
                scratchPose.translate(0F, 0F, -0.09375F * (renderCount - 1) * 0.5F + 0.09375F * k);
            }

            if (k > 0) {
                if (flag) {
                    float ox = (random.nextFloat() * 2F - 1F) * 0.15F;
                    float oy = (random.nextFloat() * 2F - 1F) * 0.15F;
                    float oz = (random.nextFloat() * 2F - 1F) * 0.15F;
                    scratchPose.translate(ox, oy, oz);
                } else {
                    float ox = (random.nextFloat() * 2F - 1F) * 0.15F * 0.5F;
                    float oy = (random.nextFloat() * 2F - 1F) * 0.15F * 0.5F;
                    scratchPose.translate(ox, oy, 0F);
                }
            }

            TransformedInstance inst = instances.get();
            inst.setTransform(scratchPose);
            inst.light(light);
            inst.setChanged();
        }

        instances.discardExtra();
    }

    private void captureBakedProperties(ItemStack stack) {
        var model = ItemModels.getActualBakedModel(level, stack, ItemCameraTransforms.TransformType.GROUND);
        if (model == null) {
            currentIsGui3d = false;
            currentGroundScaleY = 1F;
            return;
        }
        currentIsGui3d = model.isGui3d();
        var mat = model.handlePerspective(ItemCameraTransforms.TransformType.GROUND).getRight();
        if (mat == null) {
            currentGroundScaleY = 1F;
        } else {
            currentGroundScaleY = (float) Math.sqrt(mat.m10 * mat.m10 + mat.m11 * mat.m11 + mat.m12 * mat.m12);
        }
    }

    protected static int getModelCount(ItemStack stack) {
        int count = stack.getCount();
        if (count > 48) return 5;
        if (count > 32) return 4;
        if (count > 16) return 3;
        if (count > 1) return 2;
        return 1;
    }

    @Override
    protected void _delete() {
        super._delete();
        instances.delete();
    }
}
