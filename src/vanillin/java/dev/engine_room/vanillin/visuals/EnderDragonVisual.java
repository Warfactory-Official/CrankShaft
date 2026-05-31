package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.util.LightTexture;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.DragonEntityModel;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.entity.boss.EntityDragon;
import org.joml.Matrix4f;

/**
 * Ender dragon — bespoke (no standard repose): {@code ModelDragon} animates entirely in {@code render()} and draws
 * one spine 17× along {@code EntityDragon.getMovementOffsets}, so {@code pose} ports that forward kinematics into
 * per-instance matrices. Death dissolve/spin and the healing-crystal beam fall back to vanilla.
 */
public final class EnderDragonVisual extends AbstractEntityVisual<EntityDragon> implements SimpleDynamicVisual {
    private static final Material BODY = EntityMaterials.living("textures/entity/enderdragon/dragon.png");
    private static final Material EYES = EntityMaterials.emissive("textures/entity/enderdragon/dragon_eyes.png");
    private static final float S = 0.0625F;
    private static final float DEG = (float) (Math.PI / 180.0);
    private static final float TAU = (float) (Math.PI * 2.0);

    private final InstanceTree head;
    private final InstanceTree headEyes;
    private final InstanceTree body;
    private final InstanceTree[] wings = new InstanceTree[2];
    private final InstanceTree[] frontLegs = new InstanceTree[2];
    private final InstanceTree[] rearLegs = new InstanceTree[2];
    private final InstanceTree[] spines = new InstanceTree[17];
    private final InstanceTree[] all;

    private final Matrix4f root = new Matrix4f();
    private final Matrix4f frameA = new Matrix4f();
    private final Matrix4f frameB = new Matrix4f();
    private final Matrix4f scratch = new Matrix4f();

    private boolean hidden;
    private int lastLight = Integer.MIN_VALUE;

    public EnderDragonVisual(VisualizationContext ctx, EntityDragon entity, float partialTick) {
        super(ctx, entity, partialTick);
        head = tree(DragonEntityModel.HEAD, "dragon:head");
        headEyes = tree(DragonEntityModel.HEAD, "dragon:eyes", EYES);
        body = tree(DragonEntityModel.BODY, "dragon:body");
        for (int j = 0; j < 2; j++) {
            wings[j] = tree(DragonEntityModel.WING, "dragon:wing");
            frontLegs[j] = tree(DragonEntityModel.FRONT_LEG, "dragon:frontleg");
            rearLegs[j] = tree(DragonEntityModel.REAR_LEG, "dragon:rearleg");
        }
        for (int i = 0; i < 17; i++) {
            spines[i] = tree(DragonEntityModel.SPINE, "dragon:spine");
        }

        all = new InstanceTree[8 + spines.length];
        all[0] = head;
        all[1] = body;
        all[2] = wings[0];
        all[3] = wings[1];
        all[4] = frontLegs[0];
        all[5] = frontLegs[1];
        all[6] = rearLegs[0];
        all[7] = rearLegs[1];
        System.arraycopy(spines, 0, all, 8, spines.length);

        for (InstanceTree t : all) {
            t.traverse(i -> i.overlay(OverlayTexture.NO_OVERLAY));
        }
        seedEyes();
        pose(partialTick);
    }

    private void seedEyes() {
        headEyes.traverse(i -> {
            i.overlay(OverlayTexture.NO_OVERLAY);
            i.light(LightTexture.FULL_BRIGHT);
        });
    }

    private InstanceTree tree(int part, String cacheKey) {
        return tree(part, cacheKey, BODY);
    }

    private InstanceTree tree(int part, String cacheKey, Material material) {
        return InstanceTree.create(instancerProvider(),
                AbstractLivingEntityVisual.buildTree(new DragonEntityModel(part), material, cacheKey), 0);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        boolean shouldHide = entity.deathTicks > 0 || entity.deathTime > 0 || entity.isInvisible();
        if (shouldHide != hidden) {
            hidden = shouldHide;
            for (InstanceTree t : all) {
                t.visible(!hidden);
            }
            headEyes.visible(!hidden);
            if (!hidden) {
                lastLight = Integer.MIN_VALUE;
                seedEyes(); // reveal frees slots to light 0; restore fullbright
            }
        }
        if (hidden) {
            return;
        }
        pose(ctx.partialTick());
    }

    private void pose(float partialTick) {
        float fA = entity.prevAnimTime + (entity.animTime - entity.prevAnimTime) * partialTick;
        float jaw = (float) (Math.sin(fA * TAU) + 1.0) * 0.2F;
        float s0 = (float) (Math.sin(fA * TAU - 1.0F) + 1.0);
        float breathe = (s0 * s0 + s0 * 2.0F) * 0.05F;
        float f8 = fA * TAU;

        double[] seg5 = entity.getMovementOffsets(5, partialTick);
        double[] seg10 = entity.getMovementOffsets(10, partialTick);

        // RenderDragon.applyRotations + prepareScale (flip + the -1.5078 offset). Death spin deferred.
        float yaw = (float) entity.getMovementOffsets(7, partialTick)[0];
        float pitch = (float) (seg5[1] - seg10[1]);
        translateToInterpolatedPosition(root, partialTick);
        root.rotateY((float) Math.toRadians(-yaw));
        root.rotateX((float) Math.toRadians(pitch * 10.0F));
        root.translate(0.0F, 0.0F, 1.0F);
        root.scale(-1.0F, -1.0F, 1.0F);
        root.translate(0.0F, -1.5078125F, 0.0F);

        frameA.set(root);
        frameA.translate(0.0F, breathe - 2.0F, -3.0F);
        frameA.rotateX((float) Math.toRadians(breathe * 2.0F));

        double[] neckRef = entity.getMovementOffsets(6, partialTick);
        float f6 = updateRotations(seg5[0] - seg10[0]);
        float f7 = updateRotations(seg5[0] + f6 / 2.0F);

        float ay = 20.0F;
        float az = -12.0F;
        float ax = 0.0F;
        for (int i = 0; i < 5; i++) {
            double[] seg = entity.getMovementOffsets(5 - i, partialTick);
            float f9 = (float) Math.cos(i * 0.45F + f8) * 0.15F;
            float ry = updateRotations(seg[0] - neckRef[0]) * DEG * 1.5F;
            float rx = f9 + entity.getHeadPartYOffset(i, neckRef, seg) * DEG * 1.5F * 5.0F;
            float rz = -updateRotations(seg[0] - f7) * DEG * 1.5F;
            poseSpine(spines[i], ax, ay, az, rx, ry, rz, frameA);
            ay += (float) (Math.sin(rx) * 10.0);
            az -= (float) (Math.cos(ry) * Math.cos(rx) * 10.0);
            ax -= (float) (Math.sin(ry) * Math.cos(rx) * 10.0);
        }

        double[] headSeg = entity.getMovementOffsets(0, partialTick);
        float hx = ax * S;
        float hy = ay * S;
        float hz = az * S;
        float hrx = updateRotations(entity.getHeadPartYOffset(6, neckRef, headSeg)) * DEG * 1.5F * 5.0F;
        float hry = updateRotations(headSeg[0] - neckRef[0]) * DEG;
        float hrz = -updateRotations(headSeg[0] - f7) * DEG;
        poseHead(head, hx, hy, hz, hrx, hry, hrz, jaw);
        poseHead(headEyes, hx, hy, hz, hrx, hry, hrz, jaw);

        frameB.set(frameA);
        frameB.translate(0.0F, 1.0F, 0.0F);
        frameB.rotateZ((float) Math.toRadians(-f6 * 1.5F));
        frameB.translate(0.0F, -1.0F, 0.0F);
        body.updateInstances(frameB);

        float wingRx = 0.125F - (float) Math.cos(f8) * 0.2F;
        float wingRz = ((float) Math.sin(f8) + 0.125F) * 0.8F;
        float wingTipRz = -((float) Math.sin(f8 + 2.0F) + 0.5F) * 0.75F;
        float rearRx = 1.0F + breathe * 0.1F;
        float rearTipRx = 0.5F + breathe * 0.1F;
        float rearFootRx = 0.75F + breathe * 0.1F;
        float frontRx = 1.3F + breathe * 0.1F;
        float frontTipRx = -0.5F - breathe * 0.1F;
        float frontFootRx = 0.75F + breathe * 0.1F;
        for (int j = 0; j < 2; j++) {
            wings[j].child(0).rotation(wingRx, 0.25F, wingRz);
            wings[j].child(0).child(0).rotation(0.0F, 0.0F, wingTipRz);
            frontLegs[j].child(0).rotation(frontRx, 0.0F, 0.0F);
            frontLegs[j].child(0).child(0).rotation(frontTipRx, 0.0F, 0.0F);
            frontLegs[j].child(0).child(0).child(0).rotation(frontFootRx, 0.0F, 0.0F);
            rearLegs[j].child(0).rotation(rearRx, 0.0F, 0.0F);
            rearLegs[j].child(0).child(0).rotation(rearTipRx, 0.0F, 0.0F);
            rearLegs[j].child(0).child(0).child(0).rotation(rearFootRx, 0.0F, 0.0F);
            scratch.set(frameB);
            if (j == 1) {
                scratch.scale(-1.0F, 1.0F, 1.0F);
            }
            wings[j].updateInstances(scratch);
            frontLegs[j].updateInstances(scratch);
            rearLegs[j].updateInstances(scratch);
        }

        float f10 = 0.0F;
        ay = 10.0F;
        az = 60.0F;
        ax = 0.0F;
        double[] tailRef = entity.getMovementOffsets(11, partialTick);
        for (int k = 0; k < 12; k++) {
            double[] seg = entity.getMovementOffsets(12 + k, partialTick);
            f10 += (float) (Math.sin(k * 0.45F + f8) * 0.05F);
            float ry = (updateRotations(seg[0] - tailRef[0]) * 1.5F + 180.0F) * DEG;
            float rx = f10 + (float) (seg[1] - tailRef[1]) * DEG * 1.5F * 5.0F;
            float rz = updateRotations(seg[0] - f7) * DEG * 1.5F;
            // Vanilla draws the tail after popMatrix — the root frame, without frameA's breathe translate/pitch.
            poseSpine(spines[5 + k], ax, ay, az, rx, ry, rz, root);
            ay += (float) (Math.sin(rx) * 10.0);
            az -= (float) (Math.cos(ry) * Math.cos(rx) * 10.0);
            ax -= (float) (Math.sin(ry) * Math.cos(rx) * 10.0);
        }

        int light = computePackedLight(partialTick);
        if (light != lastLight) {
            lastLight = light;
            for (InstanceTree t : all) {
                t.traverse(i -> {
                    i.light(light);
                    i.overlay(OverlayTexture.NO_OVERLAY);
                    i.colorArgb(0xFFFFFFFF);
                });
            }
        }
    }

    private static void poseSpine(InstanceTree spine, float ax, float ay, float az, float rx, float ry, float rz, Matrix4f frame) {
        InstanceTree node = spine.child(0);
        node.pos(ax * S, ay * S, az * S);
        node.rotation(rx, ry, rz);
        spine.updateInstances(frame);
    }

    private void poseHead(InstanceTree headTree, float x, float y, float z, float rx, float ry, float rz, float jaw) {
        InstanceTree node = headTree.child(0);
        node.pos(x, y, z);
        node.rotation(rx, ry, rz);
        node.child(0).rotation(jaw, 0.0F, 0.0F);
        headTree.updateInstances(frameA);
    }

    private static float updateRotations(double angle) {
        while (angle >= 180.0) {
            angle -= 360.0;
        }
        while (angle < -180.0) {
            angle += 360.0;
        }
        return (float) angle;
    }

    @Override
    protected void _delete() {
        for (InstanceTree t : all) {
            t.delete();
        }
        headEyes.delete();
    }
}
