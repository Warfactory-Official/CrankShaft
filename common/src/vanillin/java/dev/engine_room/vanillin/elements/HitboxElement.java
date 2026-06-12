package dev.engine_room.vanillin.elements;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.LineModelBuilder;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.util.SmartRecycler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public final class HitboxElement implements Visual, SimpleDynamicVisual {
    //    010------110
    //    /|       /|
    //   / |      / |
    // 011------111 |
    //  |  |     |  |
    //  | 000----|-100
    //  | /      | /
    //  |/       |/
    // 001------101
    public static final Model BOX_MODEL = new LineModelBuilder(12)
            // Starting from 0, 0, 0
            .line(0, 0, 0, 0, 0, 1)
            .line(0, 0, 0, 0, 1, 0)
            .line(0, 0, 0, 1, 0, 0)
            // Starting from 0, 1, 1
            .line(0, 1, 1, 0, 1, 0)
            .line(0, 1, 1, 0, 0, 1)
            .line(0, 1, 1, 1, 1, 1)
            // Starting from 1, 0, 1
            .line(1, 0, 1, 1, 0, 0)
            .line(1, 0, 1, 1, 1, 1)
            .line(1, 0, 1, 0, 0, 1)
            // Starting from 1, 1, 0
            .line(1, 1, 0, 1, 1, 1)
            .line(1, 1, 0, 1, 0, 0)
            .line(1, 1, 0, 0, 1, 0)
            .build();

    public static final Model LINE_MODEL = new LineModelBuilder(1).line(0, 0, 0, 0, 2, 0)
                                                                  .build();

    private final VisualizationContext context;
    private final Entity entity;

    private final SmartRecycler<Model, TransformedInstance> recycler;

    private final Matrix4f scratch = new Matrix4f();

    private boolean showEyeBox;

    public HitboxElement(VisualizationContext context, Entity entity, float partialTick) {
        this.context = context;
        this.entity = entity;
        this.showEyeBox = entity instanceof LivingEntity;

        this.recycler = new SmartRecycler<>(this::createInstance);

        animate(partialTick);
    }

    public HitboxElement(VisualizationContext context, Entity entity, float partialTick, boolean showEyeBox) {
        this(context, entity, partialTick);
        this.showEyeBox = showEyeBox;
    }

    private TransformedInstance createInstance(Model model) {
        TransformedInstance instance = context.instancerProvider()
                                              .instancer(InstanceTypes.TRANSFORMED, model)
                                              .createInstance();
        instance.light(LightCoordsUtil.FULL_BRIGHT);
        instance.setChanged();
        return instance;
    }

    public boolean doesShowEyeBox() {
        return showEyeBox;
    }

    public HitboxElement showEyeBox(boolean showEyeBox) {
        this.showEyeBox = showEyeBox;
        return this;
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        animate(context.partialTick());
    }

    @Override
    public void update(float partialTick) {

    }

    @Override
    public void delete() {
        recycler.delete();
    }

    public void animate(float partialTick) {
        recycler.resetCount();

        Minecraft mc = Minecraft.getInstance();
        boolean shouldRenderHitBoxes = mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES);
        if (shouldRenderHitBoxes && !entity.isInvisible() && !mc.showOnlyReducedInfo()) {
            var renderOrigin = context.renderOrigin();

            double entityX = Mth.lerp(partialTick, entity.xOld, entity.getX());
            double entityY = Mth.lerp(partialTick, entity.yOld, entity.getY());
            double entityZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

            AABB bb = entity.getBoundingBox();

            double boxX = entityX + bb.minX - entity.getX() - renderOrigin.getX();
            double boxY = entityY + bb.minY - entity.getY() - renderOrigin.getY();
            double boxZ = entityZ + bb.minZ - entity.getZ() - renderOrigin.getZ();

            float widthX = (float) (bb.maxX - bb.minX);
            float widthY = (float) (bb.maxY - bb.minY);
            float widthZ = (float) (bb.maxZ - bb.minZ);
            scratch.identity()
                   .translate((float) boxX, (float) boxY, (float) boxZ)
                   .scale(widthX, widthY, widthZ);
            recycler.get(BOX_MODEL)
                    .setTransform(scratch)
                    .setChanged();

            // TODO: multipart entities, but forge seems to have an
            //  injection for them so we'll need platform specific code.

            if (showEyeBox) {
                double eyeY = entityY + entity.getEyeHeight() - 0.01 - renderOrigin.getY();
                scratch.identity()
                       .translate((float) boxX, (float) eyeY, (float) boxZ)
                       .scale(widthX, 0.02f, widthZ);
                recycler.get(BOX_MODEL)
                        .setTransform(scratch)
                        .color(255, 0, 0)
                        .setChanged();
            }

            Vec3 viewVector = entity.getViewVector(partialTick);
            double lineX = entityX - renderOrigin.getX();
            double lineY = entityY + entity.getEyeHeight() - renderOrigin.getY();
            double lineZ = entityZ - renderOrigin.getZ();

            scratch.identity()
                   .translate((float) lineX, (float) lineY, (float) lineZ)
                   .rotate(new Quaternionf().rotateTo(0, 1, 0,
                           (float) viewVector.x, (float) viewVector.y, (float) viewVector.z));
            recycler.get(LINE_MODEL)
                    .setTransform(scratch)
                    .color(0, 0, 255)
                    .setChanged();
        }

        recycler.discardExtra();
    }
}
