package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.compat.DynamicLightProvider;
import dev.engine_room.flywheel.lib.compat.animation.SmartAnimatedTextureCompat;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.item.ItemModels;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ModelManager;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

public class ItemFrameVisual extends AbstractEntityVisual<EntityItemFrame> implements SimpleDynamicVisual {
    private static final ModelResourceLocation FRAME_LOCATION = new ModelResourceLocation("item_frame", "normal");
    private static final ModelResourceLocation MAP_FRAME_LOCATION = new ModelResourceLocation("item_frame", "map");

    private final Matrix4f baseTransform = new Matrix4f();
    private final Matrix4f itemPose = new Matrix4f();

    private TransformedInstance frame;
    private TransformedInstance item;
    private Model frameModel;
    private Model itemModel;
    private ModelResourceLocation lastFrameLocation;
    private ItemStack lastItemStack;

    public ItemFrameVisual(VisualizationContext ctx, EntityItemFrame entity, float partialTick) {
        super(ctx, entity, partialTick);

        lastItemStack = entity.getDisplayedItem().copy();
        lastFrameLocation = getFrameModelResourceLoc(lastItemStack);
        frame = createFrameInstance(lastFrameLocation);
        item = createItemInstance(lastItemStack);

        animate(partialTick);
    }

    public static boolean shouldVisualize(EntityItemFrame entity) {
        // Maps need custom render logic vanilla handles directly; bail to vanilla for those.
        ItemStack stack = entity.getDisplayedItem();
        if (stack.getItem().isMap()) return false;
        return stack.isEmpty() || ItemModels.isSupported(stack);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        SmartAnimatedTextureCompat.touch(frameModel);
        SmartAnimatedTextureCompat.touch(itemModel);
        animate(ctx.partialTick());
    }

    private void animate(float partialTick) {
        int light = computePackedLight(partialTick);
        boolean invisible = entity.isInvisible();

        var origin = visualizationContext.renderOrigin();

        // ItemModels.bakeMesh bakes in T(-0.5) post-perspective, so we omit it here. Upstream
        // Flywheel keeps the T(-0.5) in the animate path instead; we diverge by baking it.
        BlockPos hanging = entity.getHangingPosition();
        float x = hanging.getX() + 0.5F - origin.getX();
        float y = hanging.getY() + 0.5F - origin.getY();
        float z = hanging.getZ() + 0.5F - origin.getZ();

        baseTransform.translation(x, y, z);
        baseTransform.rotateY((float) Math.toRadians(180F - entity.rotationYaw));
        baseTransform.rotateX((float) Math.toRadians(entity.rotationPitch));

        ItemStack stack = entity.getDisplayedItem();
        ModelResourceLocation frameLocation = getFrameModelResourceLoc(stack);
        if (frameLocation != lastFrameLocation) {
            Model model = bakeFrameModel(frameLocation);
            frameModel = model;
            visualizationContext.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, model)
                    .stealInstance(frame);
            lastFrameLocation = frameLocation;
        }

        frame.setVisible(!invisible);
        frame.setTransform(baseTransform);
        frame.light(light);
        frame.setChanged();

        if (!ItemStack.areItemStacksEqual(lastItemStack, stack)) {
            lastItemStack = stack.copy();
            Model model = bakeItemModel(lastItemStack);
            itemModel = model;
            visualizationContext.instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, model)
                    .stealInstance(item);
        }

        boolean hasItem = !stack.isEmpty();
        item.setVisible(hasItem);
        if (hasItem) {
            itemPose.set(baseTransform);
            itemPose.translate(0F, 0F, invisible ? 0.5F : 0.4375F);
            int rotIndex = (stack.getItem() instanceof ItemMap) ? (entity.getRotation() % 4) * 2 : entity.getRotation();
            itemPose.rotateZ((float) Math.toRadians(rotIndex * 360F / 8F));
            itemPose.scale(0.5F, 0.5F, 0.5F);

            item.setTransform(itemPose);
            item.light(light);
            item.setChanged();
        }
    }

    private Model bakeFrameModel(ModelResourceLocation frameLocation) {
        ModelManager modelManager = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelManager();
        IBakedModel baked = modelManager.getModel(frameLocation);
        return ItemModels.getForModel(baked, ItemCameraTransforms.TransformType.NONE);
    }

    private Model bakeItemModel(ItemStack stack) {
        return ItemModels.get(level, stack, ItemCameraTransforms.TransformType.FIXED);
    }

    private TransformedInstance createFrameInstance(ModelResourceLocation frameLocation) {
        Model model = bakeFrameModel(frameLocation);
        frameModel = model;
        return visualizationContext.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, model)
                .createInstance();
    }

    private TransformedInstance createItemInstance(ItemStack stack) {
        Model model = bakeItemModel(stack);
        itemModel = model;
        return visualizationContext.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, model)
                .createInstance();
    }

    private static ModelResourceLocation getFrameModelResourceLoc(ItemStack stack) {
        return stack.getItem() instanceof ItemMap ? MAP_FRAME_LOCATION : FRAME_LOCATION;
    }

    @Override
    protected void _delete() {
        frame.delete();
        item.delete();
    }

    // Item frames live at the block they're nailed to, not at posY + eyeHeight.
    @Override
    protected int computePackedLight(float partialTick) {
        return DynamicLightProvider.INSTANCE.getLightForEntity(entity, entity.getHangingPosition());
    }
}
