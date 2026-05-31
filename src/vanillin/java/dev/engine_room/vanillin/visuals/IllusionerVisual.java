package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.HeldItemLayer;
import dev.engine_room.flywheel.lib.visual.IllagerEntityModel;
import net.minecraft.client.model.ModelIllager;
import net.minecraft.entity.monster.AbstractIllager;
import net.minecraft.entity.monster.EntityIllusionIllager;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** Illusioner. Renders even while invisible (the mirror illusion IS the render): copy 0 is this body (offset in
 *  {@link #buildRoot}), copies 1-3 posed off it by {@link IllusionerMirrorLayer}. Keeps its hat (unmasked). */
public final class IllusionerVisual extends AbstractLivingEntityVisual<EntityIllusionIllager, ModelIllager> {
    static final Material BODY = EntityMaterials.living("textures/entity/illager/illusionist.png");

    public IllusionerVisual(VisualizationContext ctx, EntityIllusionIllager entity, float partialTick) {
        super(ctx, entity, partialTick, new IllagerEntityModel(), BODY, "illusioner", 0.5F);
        addLayer(new HeldItemLayer(ctx, entity, instances, 5, 1,
                e -> ((EntityIllusionIllager) e).isSpellcasting() || ((EntityIllusionIllager) e).isAggressive()));
        addLayer(new IllusionerMirrorLayer(ctx, entity, instances, BODY, 0));
    }

    @Override
    protected boolean shouldHide() {
        return false;
    }

    @Override
    protected void poseModel(float partialTick) {
        super.poseModel(partialTick);
        boolean crossed = entity.getArmPose() == AbstractIllager.IllagerArmPose.CROSSED;
        setRootSkipDraw(4, !crossed);
        setRootSkipDraw(5, crossed);
        setRootSkipDraw(6, crossed);
    }

    @Override
    protected void preRenderCallback(Matrix4f dest, float partialTick) {
        dest.scale(0.9375F);
    }

    @Override
    protected void buildRoot(Matrix4f dest, float partialTick) {
        super.buildRoot(dest, partialTick);
        if (entity.isInvisible()) {
            // Copy 0's world offset, applied in world space.
            Vec3d o = offset(entity, 0, partialTick);
            dest.translateLocal((float) o.x, (float) o.y, (float) o.z);
        }
    }

    /** RenderIllusionIllager.doRender: copy {@code i} sits at {@code getRenderLocations[i]} plus a per-copy cosine wobble. */
    static Vec3d offset(EntityIllusionIllager entity, int i, float partialTick) {
        Vec3d[] locs = entity.getRenderLocations(partialTick);
        float f = entity.ticksExisted + partialTick;
        return locs[i].add(
                MathHelper.cos(i + f * 0.5F) * 0.025,
                MathHelper.cos(i + f * 0.75F) * 0.0125,
                MathHelper.cos(i + f * 0.7F) * 0.025);
    }
}
