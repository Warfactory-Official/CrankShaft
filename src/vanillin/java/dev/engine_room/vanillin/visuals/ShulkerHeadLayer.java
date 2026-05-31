package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.UvTransformedInstance;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.texture.VariantAtlas;
import dev.engine_room.flywheel.lib.texture.VariantAtlasHolder;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractLivingEntityVisual;
import dev.engine_room.flywheel.lib.visual.LivingLayer;
import dev.engine_room.flywheel.lib.visual.ShulkerHeadEntityModel;
import net.minecraft.client.model.ModelShulker;
import net.minecraft.entity.monster.EntityShulker;
import net.minecraft.util.EnumFacing;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The shulker's head turret — the inner cube that aims at the player while the lid is open (vanilla
 * {@code RenderShulker.HeadLayer}). It rides the body's 16-colour atlas (same texture, head UVs at offset 0,52),
 * so it draws on {@link InstanceTypes#UV_TRANSFORMED} with the body's cell. Vanilla composes a second
 * per-attachment-face transform on top of the body's (already in {@code rootPose}); we post-multiply the same
 * sequence onto {@code rootPose}, then pose the head off the body scratch model's {@code head} bone (yaw/pitch
 * set by {@code ModelShulker.setRotationAngles} during the body's pose pass).
 */
public final class ShulkerHeadLayer implements LivingLayer {
    private static final float DEG90 = (float) Math.toRadians(90.0);
    private static final float DEG180 = (float) Math.toRadians(180.0);

    private final EntityShulker shulker;
    private final ModelShulker bodyModel;
    private final InstanceTree head;
    private final VariantAtlasHolder atlas;
    private final Matrix4f scratch = new Matrix4f();

    private boolean parentVisible = true;
    private boolean shown;
    private int lastLight = Integer.MIN_VALUE;
    private int lastOverlay = -1;
    private VariantAtlas.Cell lastCell = null;

    public ShulkerHeadLayer(VisualizationContext ctx, EntityShulker shulker, ModelShulker bodyModel,
                            VariantAtlasHolder atlas, int bias) {
        this.shulker = shulker;
        this.bodyModel = bodyModel;
        this.atlas = atlas;
        this.head = InstanceTree.create(ctx.instancerProvider(),
                AbstractLivingEntityVisual.buildTree(new ShulkerHeadEntityModel(), atlas.material(), "shulker:head"),
                bias, InstanceTypes.UV_TRANSFORMED);
        // Seeded instances sit at the render origin; stay hidden until the first un-culled beginFrame poses them.
        head.visible(false);
    }

    @Override
    public void beginFrame(Matrix4fc rootPose, int light, float partialTick, boolean bodyMoved) {
        if (!parentVisible) {
            return;
        }
        if (!shown) {
            shown = true;
            head.visible(true);
            // Revealing reseeds the freed slab slots; force the guarded traverse to re-push.
            lastLight = Integer.MIN_VALUE;
            lastOverlay = -1;
            lastCell = null;
        }

        scratch.set(rootPose);
        applyAttachFace(scratch, shulker.getAttachmentFacing());
        head.child(0).copyTransform(bodyModel.head);

        int overlay = OverlayTexture.forEntity(shulker);
        VariantAtlas.Cell cell = atlas.cell(ShulkerVisual.skinIndex(shulker));
        if (light != lastLight || overlay != lastOverlay || cell != lastCell) {
            lastLight = light;
            lastOverlay = overlay;
            lastCell = cell;
            head.traverse(i -> {
                i.light(light);
                i.overlay(overlay);
                i.colorArgb(0xFFFFFFFF);
                if (i instanceof UvTransformedInstance u) {
                    u.uvRegion(cell);
                }
            });
        }
        head.updateInstances(scratch);
    }

    // RenderShulker.HeadLayer: a second attach-face transform composed on the post-flip body frame.
    private static void applyAttachFace(Matrix4f m, EnumFacing face) {
        switch (face) {
            case EAST:
                m.rotateZ(DEG90);
                m.rotateX(DEG90);
                m.translate(1.0F, -1.0F, 0.0F);
                m.rotateY(DEG180);
                break;
            case WEST:
                m.rotateZ(-DEG90);
                m.rotateX(DEG90);
                m.translate(-1.0F, -1.0F, 0.0F);
                m.rotateY(DEG180);
                break;
            case NORTH:
                m.rotateX(DEG90);
                m.translate(0.0F, -1.0F, -1.0F);
                break;
            case SOUTH:
                m.rotateZ(DEG180);
                m.rotateX(DEG90);
                m.translate(0.0F, -1.0F, 1.0F);
                break;
            case UP:
                m.rotateX(DEG180);
                m.translate(0.0F, -2.0F, 0.0F);
                break;
            case DOWN:
            default:
                break;
        }
    }

    @Override
    public void setVisible(boolean visible) {
        parentVisible = visible;
        if (!visible && shown) {
            shown = false;
            head.visible(false);
        }
    }

    @Override
    public void delete() {
        head.delete();
    }
}
