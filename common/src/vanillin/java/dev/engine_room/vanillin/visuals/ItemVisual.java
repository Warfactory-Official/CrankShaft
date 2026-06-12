package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.FireComponent;
import dev.engine_room.flywheel.lib.visual.component.NameTagComponent;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import dev.engine_room.vanillin.item.ItemModels;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class ItemVisual extends AbstractEntityVisual<ItemEntity> implements SimpleDynamicVisual {
    private static final float ITEM_MIN_HOVER_HEIGHT = 0.0625f;
    private static final float FLAT_ITEM_DEPTH_THRESHOLD = 0.0625f;
    private static final TransformedInstance[] NO_INSTANCES = new TransformedInstance[0];
    private static final Vector3f[] NO_OFFSETS = new Vector3f[0];
    private final ShadowComponent shadowComponent;
    private final FireComponent fireComponent;
    private final NameTagComponent nameTagComponent;
    private final Matrix4f pose = new Matrix4f();
    private ItemStack currentStack = ItemStack.EMPTY;
    private TransformedInstance[] instances = NO_INSTANCES;
    private Vector3f[] offsets = NO_OFFSETS;
    private float minOffsetY;

    public ItemVisual(VisualizationContext ctx, ItemEntity entity, float partialTick) {
        super(ctx, entity, partialTick);
        shadowComponent = new ShadowComponent(ctx, entity);
        fireComponent = new FireComponent(ctx, entity);
        nameTagComponent = new NameTagComponent(ctx, entity);
        updateStack();
        animate(partialTick);
    }

    public static boolean isSupported(ItemEntity entity) {
        return ItemModels.isSupported(entity.getItem(), ItemDisplayContext.GROUND, entity, entity.getId());
    }

    private static Vector3f[] computeOffsets(int count, int seed, float modelDepth) {
        Vector3f[] offsets = new Vector3f[count];
        RandomSource random = RandomSource.create();
        random.setSeed(seed);
        if (modelDepth > FLAT_ITEM_DEPTH_THRESHOLD) {
            offsets[0] = new Vector3f();
            for (int i = 1; i < count; i++) {
                offsets[i] = new Vector3f((random.nextFloat() * 2.0f - 1.0f) * 0.15f,
                        (random.nextFloat() * 2.0f - 1.0f) * 0.15f,
                        (random.nextFloat() * 2.0f - 1.0f) * 0.15f);
            }
        } else {
            // Flat cards stack along Z, centered on the entity, with XY-only jitter past the first.
            float offsetZ = modelDepth * 1.5f;
            float z = -(offsetZ * (count - 1) / 2.0f);
            offsets[0] = new Vector3f(0.0f, 0.0f, z);
            for (int i = 1; i < count; i++) {
                z += offsetZ;
                offsets[i] = new Vector3f((random.nextFloat() * 2.0f - 1.0f) * 0.15f * 0.5f,
                        (random.nextFloat() * 2.0f - 1.0f) * 0.15f * 0.5f, z);
            }
        }
        return offsets;
    }

    private void updateStack() {
        currentStack = entity.getItem();
        deleteInstances();
        minOffsetY = 0.0f;
        // Honor the gate BOTH ways: a gate-rejected stack is vanilla's even when its own bake would produce meshes (a sibling stack may have demoted the key's verdict).
        if (!isSupported(entity)) {
            return;
        }
        var baked = ItemModels.bake(currentStack, ItemDisplayContext.GROUND, entity, entity.getId());
        if (baked.model()
                 .meshes()
                 .isEmpty()) {
            return;
        }
        minOffsetY = -baked.modelMinY() + ITEM_MIN_HOVER_HEIGHT;

        int count = ItemClusterRenderState.getRenderedAmount(currentStack.getCount());
        offsets = computeOffsets(count, ItemClusterRenderState.getSeedForItemStack(currentStack), baked.modelZSize());
        var instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, baked.model());
        instances = new TransformedInstance[count];
        for (int i = 0; i < count; i++) {
            instances[i] = instancer.createInstance();
        }
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        if (!isVisible(context.frustum())) {
            return;
        }
        if (!ItemStack.matches(entity.getItem(), currentStack)) {
            updateStack();
        } else if (instances.length != 0 && !isSupported(entity)) {
            deleteInstances();
        }
        if (instances.length == 0) {
            shadowComponent.radius(0.0f);
            shadowComponent.beginFrame(context);
            fireComponent.delete();
            nameTagComponent.delete();
            return;
        }
        animate(context.partialTick());
        shadowComponent.radius(0.15f);
        shadowComponent.strength((float) ((1.0 - entity.distanceToSqr(context.camera()
                                                                             .position()) / 256.0) * 0.75));
        shadowComponent.beginFrame(context);
        fireComponent.beginFrame(context);
        nameTagComponent.beginFrame(context);
    }

    private void animate(float partialTick) {
        if (instances.length == 0) {
            return;
        }
        var renderOrigin = renderOrigin();
        double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - renderOrigin.getX();
        double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - renderOrigin.getY();
        double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - renderOrigin.getZ();

        // 26.2 drives bob + spin off tickCount + partialTick -- NOT ItemEntity.age, which resets on stack merges.
        float age = entity.tickCount + partialTick;
        float bob = Mth.sin(age / 10.0F + entity.bobOffs) * 0.1F + 0.1F;
        float spin = ItemEntity.getSpin(age, entity.bobOffs);
        int light = computePackedLight(partialTick);

        for (int i = 0; i < instances.length; i++) {
            pose.translation((float) x, (float) (y + bob + minOffsetY), (float) z)
                .rotateY(spin)
                .translate(offsets[i]);
            instances[i].setTransform(pose)
                        .light(light)
                        .setChanged();
        }
    }

    private void deleteInstances() {
        for (TransformedInstance instance : instances) {
            instance.delete();
        }
        instances = NO_INSTANCES;
        offsets = NO_OFFSETS;
    }

    @Override
    protected void _delete() {
        deleteInstances();
        shadowComponent.delete();
        fireComponent.delete();
        nameTagComponent.delete();
    }
}
