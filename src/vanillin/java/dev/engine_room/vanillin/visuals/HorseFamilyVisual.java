package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.HorseEntityModel;
import net.minecraft.client.model.ModelHorse;
import net.minecraft.entity.passive.AbstractChestHorse;
import net.minecraft.entity.passive.AbstractHorse;
import org.joml.Matrix4f;

/**
 * Shared visual for the single-texture horse family (skeleton/zombie horse, donkey, mule) over
 * {@code HorseEntityModel}. Per-type {@code scale} (donkey 0.87, mule 0.92, others 1.0) rides
 * {@code preRenderCallback}; chest-horses toggle their two chest boxes per frame on {@code hasChest}; the saddle/
 * rein tack toggles on {@code isHorseSaddled} (reins additionally on {@code isBeingRidden}). Subclassed by
 * {@link HorseVisual} for the markings-atlas horse.
 */
public class HorseFamilyVisual extends AbstractLivingEntityVisual<AbstractHorse, ModelHorse> {
    private final HorseEntityModel horseModel;
    private final float scale;
    private final int chestRoot;
    private final int tackStart;

    public HorseFamilyVisual(VisualizationContext ctx, AbstractHorse entity, float partialTick,
                             HorseEntityModel model, Material material, String cacheKey,
                             float scale, int chestRoot, int tackStart) {
        super(ctx, entity, partialTick, model, material, cacheKey, 0.75F);
        this.horseModel = model;
        this.scale = scale;
        this.chestRoot = chestRoot;
        this.tackStart = tackStart;
    }

    public static boolean isInstanceable(AbstractHorse entity) {
        return !entity.isInvisible();
    }

    @Override
    protected boolean shouldHide() {
        return !isInstanceable(entity);
    }

    @Override
    protected boolean instancesBabies() {
        return true;
    }

    @Override
    protected void poseModel(float partialTick) {
        super.poseModel(partialTick);
        // Vanilla ModelHorse.render reads grassEatingAmount(0) for the baby head group and gates
        // tack/chests on !isChild.
        horseModel.grassEatingAmount = entity.getGrassEatingAmount(0.0F);
        boolean adult = !entity.isChild();
        if (chestRoot >= 0) {
            boolean chest = adult && ((AbstractChestHorse) entity).hasChest();
            setRootSkipDraw(chestRoot, !chest);
            setRootSkipDraw(chestRoot + 1, !chest);
        }
        boolean saddled = adult && entity.isHorseSaddled();
        for (int i = 0; i < 10; i++) {
            setRootSkipDraw(tackStart + i, !saddled);
        }
        boolean reins = saddled && entity.isBeingRidden();
        setRootSkipDraw(tackStart + 10, !reins);
        setRootSkipDraw(tackStart + 11, !reins);
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        if (scale != 1.0F) {
            dest.scale(scale);
        }
    }
}
