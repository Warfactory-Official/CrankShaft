package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.vanillin.item.ItemModels;
import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/**
 * Instanced flat-item projectile visual mirroring {@code ThrownItemRenderer}: one billboarded GROUND-baked instance.
 */
public class ThrownItemVisual<T extends Entity & ItemSupplier> extends AbstractEntityVisual<T> implements SimpleDynamicVisual {
    private final float scale;
    private final boolean fullBright;
    private final Matrix4f pose = new Matrix4f();
    private ItemStack currentStack = ItemStack.EMPTY;
    @Nullable
    private TransformedInstance instance;

    public ThrownItemVisual(VisualizationContext ctx, T entity, float partialTick, float scale, boolean fullBright) {
        super(ctx, entity, partialTick);
        this.scale = scale;
        this.fullBright = fullBright;
        updateItem();
        if (instance != null) {
            animate(partialTick, Minecraft.getInstance().gameRenderer.mainCamera().rotation());
        }
    }

    public static <T extends Entity & ItemSupplier> boolean isSupported(T entity) {
        return ItemModels.isSupported(entity.getItem(), ItemDisplayContext.GROUND, entity, entity.getId());
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        if (!ItemStack.matches(entity.getItem(), currentStack)) {
            updateItem();
        }
        if (instance == null) {
            return;
        }
        animate(ctx.partialTick(), ctx.camera().rotation());
    }

    private void updateItem() {
        currentStack = entity.getItem();
        instance = ItemModels.rebake(instancerProvider(), instance, currentStack, ItemDisplayContext.GROUND, entity,
                entity.getId());
    }

    private void animate(float partialTick, Quaternionfc cameraRotation) {
        var origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ());
        pose.translation(x, y, z)
            .scale(scale)
            .rotate(cameraRotation);
        int light = computePackedLight(partialTick);
        instance.setTransform(pose)
                .light(fullBright ? LightCoordsUtil.pack(15, LightCoordsUtil.sky(light)) : light)
                .setChanged();
    }

    @Override
    protected void _delete() {
        if (instance != null) {
            instance.delete();
            instance = null;
        }
    }
}
