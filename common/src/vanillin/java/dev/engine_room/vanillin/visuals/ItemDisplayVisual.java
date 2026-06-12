package dev.engine_room.vanillin.visuals;

import com.mojang.math.Transformation;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.NameTagComponent;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import dev.engine_room.vanillin.item.ItemModels;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class ItemDisplayVisual extends AbstractEntityVisual<Display.ItemDisplay> implements SimpleDynamicVisual {
    private final TransformedInstance instance;
    private final ShadowComponent shadowComponent;
    private final NameTagComponent nameTagComponent;
    private final Matrix4f pose = new Matrix4f();
    private ItemStack currentStack;

    public ItemDisplayVisual(VisualizationContext ctx, Display.ItemDisplay entity, float partialTick) {
        super(ctx, entity, partialTick);

        var itemRenderState = entity.itemRenderState();
        if (itemRenderState == null) {
            currentStack = ItemStack.EMPTY;
            instance = ctx.instancerProvider()
                          .instancer(InstanceTypes.TRANSFORMED, Models.block(Blocks.AIR.defaultBlockState()))
                          .createInstance();
        } else {
            currentStack = itemRenderState.itemStack()
                                          .copy();
            instance = ctx.instancerProvider()
                          .instancer(InstanceTypes.TRANSFORMED,
                                  ItemModels.get(currentStack, itemRenderState.itemTransform(), entity, entity.getId()))
                          .createInstance();
        }

        shadowComponent = new ShadowComponent(ctx, entity);
        nameTagComponent = new NameTagComponent(ctx, entity);
    }

    public static boolean shouldVisualize(Display.ItemDisplay entity) {
        var state = entity.itemRenderState();
        return state != null && ItemModels.isSupported(state.itemStack(), state.itemTransform(), entity,
                entity.getId());
    }

    private static float cameraYrot(Camera camera) {
        return camera.yRot() - 180.0F;
    }

    private static float cameraXRot(Camera camera) {
        return -camera.xRot();
    }

    private static float entityYRot(Entity entity, float partialTick) {
        return Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
    }

    private static float entityXRot(Entity entity, float partialTick) {
        return Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        Display.RenderState renderState = entity.renderState();
        var object = entity.itemRenderState();
        if (isFirstPersonCameraEntity() || renderState == null || object == null
                || !ItemModels.isSupported(object.itemStack(), object.itemTransform(), entity, entity.getId())) {
            instance.setVisible(false);
            shadowComponent.radius(0.0f);
            nameTagComponent.delete();
            return;
        }

        instance.setVisible(true);
        nameTagComponent.beginFrame(ctx);

        var itemStack = object.itemStack();
        if (!ItemStack.matches(itemStack, currentStack)) {
            currentStack = itemStack.copy();
            visualizationContext.instancerProvider()
                                .instancer(InstanceTypes.TRANSFORMED,
                                        ItemModels.get(currentStack, object.itemTransform(), entity, entity.getId()))
                                .stealInstance(instance);
        }

        float f = entity.calculateInterpolationProgress(ctx.partialTick());

        shadowComponent.radius(renderState.shadowRadius()
                                          .get(f));
        shadowComponent.strength((float) (1.0 - entity.distanceToSqr(ctx.camera()
                                                                        .position()) / 256.0) * renderState.shadowStrength()
                                                                                                           .get(f));
        shadowComponent.beginFrame(ctx);

        int i = renderState.brightnessOverride();
        int j = i != -1 ? i : computePackedLight(ctx.partialTick());
        Transformation transformation = renderState.transformation()
                                                   .get(f);

        Vec3 pos = entity.position();
        var renderOrigin = renderOrigin();

        pose.translation((float) (pos.x - renderOrigin.getX()), (float) (pos.y - renderOrigin.getY()),
                (float) (pos.z - renderOrigin.getZ()));

        float partialTick = ctx.partialTick();
        Camera camera = ctx.camera();
        switch (renderState.billboardConstraints()) {
            case FIXED:
                pose.rotateYXZ(-0.017453292F * entityYRot(entity, partialTick),
                        ((float) Math.PI / 180F) * entityXRot(entity, partialTick), 0.0F);
                break;
            case HORIZONTAL:
                pose.rotateYXZ(-0.017453292F * entityYRot(entity, partialTick),
                        ((float) Math.PI / 180F) * cameraXRot(camera), 0.0F);
                break;
            case VERTICAL:
                pose.rotateYXZ(-0.017453292F * cameraYrot(camera),
                        ((float) Math.PI / 180F) * entityXRot(entity, partialTick), 0.0F);
                break;
            case CENTER:
                pose.rotateYXZ(-0.017453292F * cameraYrot(camera), ((float) Math.PI / 180F) * cameraXRot(camera), 0.0F);
                break;
        }

        pose.mul(transformation.getMatrix())
            .rotateY(Mth.PI);

        instance.setTransform(pose)
                .light(j)
                .setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
        shadowComponent.delete();
        nameTagComponent.delete();
    }
}
