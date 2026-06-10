package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.BillboardInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.item.ItemModels;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;

import java.util.function.Function;
import java.util.function.Predicate;

/** Mirrors {@code RenderSnowball}: the thrown item's GROUND-transformed model billboarded at the entity
 *  position (snowballs, eggs, pearls, eyes of ender, potions, XP bottles, firework rockets). */
public class ThrownItemVisual<T extends Entity> extends AbstractEntityVisual<T> implements SimpleDynamicVisual {
    private final Function<T, ItemStack> stackGetter;
    private final boolean fullbright;

    private ItemStack currentStack;
    private BillboardInstance instance;

    public ThrownItemVisual(VisualizationContext ctx, T entity, float partialTick,
                            Function<T, ItemStack> stackGetter, boolean fullbright) {
        super(ctx, entity, partialTick);
        this.stackGetter = stackGetter;
        this.fullbright = fullbright;
        currentStack = stackGetter.apply(entity).copy();
        createInstance(partialTick);
    }

    /** Memoizes {@link ItemModels#isSupported} for a constant stack — the answer only changes on
     *  resource reload (a pack can swap an item's model to a builtin renderer). */
    public static <T extends Entity> Predicate<T> supported(ItemStack stack) {
        return new Predicate<>() {
            private int reloadCount = -1;
            private boolean supported;

            @Override
            public boolean test(T entity) {
                int now = RendererReloadCache.reloadCount();
                if (reloadCount != now) {
                    reloadCount = now;
                    supported = ItemModels.isSupported(stack);
                }
                return supported;
            }
        };
    }

    private void createInstance(float partialTick) {
        Model model = ItemModels.get(level, currentStack, ItemCameraTransforms.TransformType.GROUND);
        instance = instancerProvider().instancer(InstanceTypes.BILLBOARD, model)
                .createInstance();
        instance.size(1.0F);
        if (fullbright) {
            // EntityEnderEye.getBrightnessForRender is constant fullbright.
            instance.light(LightTexture.FULL_BRIGHT);
        }
        updateInstance(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        ItemStack stack = stackGetter.apply(entity);
        if (!ItemStack.areItemStacksEqual(stack, currentStack)) {
            // A potion's stack syncs via the data manager and can land after the visual is created.
            instance.delete();
            currentStack = stack.copy();
            createInstance(ctx.partialTick());
            return;
        }
        updateInstance(ctx.partialTick());
    }

    private void updateInstance(float partialTick) {
        var p = getVisualPosition(partialTick);
        instance.position(p.x, p.y, p.z);
        if (!fullbright) {
            instance.light(computePackedLight(partialTick));
        }
        instance.setChanged();
    }

    @Override
    protected void _delete() {
        instance.delete();
    }
}
