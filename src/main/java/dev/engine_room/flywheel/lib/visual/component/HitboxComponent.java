package dev.engine_room.flywheel.lib.visual.component;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.LineModelBuilder;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.visual.util.SmartRecycler;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public final class HitboxComponent implements EntityComponent {
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

    public static final Model LINE_MODEL = new LineModelBuilder(1)
            .line(0, 0, 0, 0, 2, 0)
            .build();

    private final VisualizationContext context;
    private final Entity entity;
    private final SmartRecycler<Model, TransformedInstance> recycler;

    private boolean showEyeBox;

    private final Matrix4f scratch = new Matrix4f();

    public HitboxComponent(VisualizationContext context, Entity entity) {
        this.context = context;
        this.entity = entity;
        this.showEyeBox = entity instanceof EntityLivingBase;
        this.recycler = new SmartRecycler<>(this::createInstance);
    }

    private TransformedInstance createInstance(Model model) {
        TransformedInstance instance = context.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, model)
                .createInstance();
        instance.light(LightTexture.FULL_BRIGHT);
        instance.setChanged();
        return instance;
    }

    public boolean doesShowEyeBox() {
        return showEyeBox;
    }

    public HitboxComponent showEyeBox(boolean showEyeBox) {
        this.showEyeBox = showEyeBox;
        return this;
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        recycler.resetCount();

        Minecraft mc = Minecraft.getMinecraft();
        boolean shouldRenderHitBoxes = mc.getRenderManager().isDebugBoundingBox();
        if (shouldRenderHitBoxes && !entity.isInvisible() && !mc.gameSettings.reducedDebugInfo) {
            float partialTick = context.partialTick();
            var renderOrigin = this.context.renderOrigin();

            double entityX = lerp(partialTick, entity.lastTickPosX, entity.posX);
            double entityY = lerp(partialTick, entity.lastTickPosY, entity.posY);
            double entityZ = lerp(partialTick, entity.lastTickPosZ, entity.posZ);

            AxisAlignedBB bb = entity.getEntityBoundingBox();

            // Flywheel instance poses are in render-origin-relative space; the engine's
            // view-projection takes us from there to clip. Subtract renderOrigin from the
            // anchor so the wireframe lands at the entity's actual screen position.
            double boxX = entityX + bb.minX - entity.posX - renderOrigin.getX();
            double boxY = entityY + bb.minY - entity.posY - renderOrigin.getY();
            double boxZ = entityZ + bb.minZ - entity.posZ - renderOrigin.getZ();

            float widthX = (float) (bb.maxX - bb.minX);
            float widthY = (float) (bb.maxY - bb.minY);
            float widthZ = (float) (bb.maxZ - bb.minZ);
            scratch.identity()
                    .translate((float) boxX, (float) boxY, (float) boxZ)
                    .scale(widthX, widthY, widthZ);
            recycler.get(BOX_MODEL).setTransform(scratch).setChanged();

            if (showEyeBox) {
                double eyeY = entityY + entity.getEyeHeight() - 0.01 - renderOrigin.getY();
                scratch.identity()
                        .translate((float) boxX, (float) eyeY, (float) boxZ)
                        .scale(widthX, 0.02f, widthZ);
                recycler.get(BOX_MODEL).setTransform(scratch).color(255, 0, 0).setChanged();
            }

            Vec3d viewVector = entity.getLook(partialTick);
            double lineX = entityX - renderOrigin.getX();
            double lineY = entityY + entity.getEyeHeight() - renderOrigin.getY();
            double lineZ = entityZ - renderOrigin.getZ();

            scratch.identity()
                    .translate((float) lineX, (float) lineY, (float) lineZ)
                    .rotate(new Quaternionf().rotateTo(0, 1, 0,
                            (float) viewVector.x, (float) viewVector.y, (float) viewVector.z));
            recycler.get(LINE_MODEL).setTransform(scratch).color(0, 0, 255).setChanged();
        }

        recycler.discardExtra();
    }

    @Override
    public void delete() {
        recycler.delete();
    }

    private static double lerp(float t, double a, double b) {
        return a + (b - a) * t;
    }
}
