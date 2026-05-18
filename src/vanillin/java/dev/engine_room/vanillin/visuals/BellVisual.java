package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;
import thedarkcolour.futuremc.tile.BellTileEntity;

import java.util.function.Consumer;

public final class BellVisual extends AbstractBlockEntityVisual<BellTileEntity>
        implements SimpleDynamicVisual {

    private static final Material MATERIAL = SimpleMaterial.builder()
            .texture(new ResourceLocation("futuremc", "textures/entity/bell/bell_body.png"))
            .mipmap(false)
            .build();

    private static ModelTree buildTree() {
        // Geometry mirrors Future-MC's BellModel.kt (1.12.2 has no upstream BELL model layer).
        return ModelTrees.of("futuremc:bell", () -> {
            ModelBase base = new ModelBase() {};
            base.textureWidth = 32;
            base.textureHeight = 32;

            ModelRenderer bell = new ModelRenderer(base, 0, 0);
            bell.addBox(-3F, -6F, -3F, 6, 7, 6);
            bell.setRotationPoint(8F, 12F, 8F);

            ModelRenderer fixture = new ModelRenderer(base, 0, 13);
            fixture.addBox(4F, 4F, 4F, 8, 2, 8);
            fixture.setRotationPoint(-8F, -12F, -8F);

            bell.addChild(fixture);
            return bell;
        }, MATERIAL);
    }

    private final InstanceTree instances;
    private final Matrix4f initialPose;
    private int packedLight;
    private float lastXRot = Float.NaN;
    private float lastZRot = Float.NaN;

    public BellVisual(VisualizationContext ctx, BellTileEntity te, float partialTick) {
        super(ctx, te, partialTick);

        instances = InstanceTree.create(instancerProvider(), buildTree());

        packedLight = computePackedLight();
        instances.traverse(inst -> {
            inst.light(packedLight);
            inst.overlay(OverlayTexture.NO_OVERLAY);
        });

        var origin = visualizationContext.renderOrigin();
        // No (1,-1,-1) flip: FutureMC's TESR renders Y-up unflipped, so the model is authored
        // that way and we just translate to the block position.
        initialPose = new Matrix4f().translate(
                pos.getX() - origin.getX(),
                pos.getY() - origin.getY(),
                pos.getZ() - origin.getZ());

        writePose(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (doDistanceLimitThisFrame(ctx)) {
            return;
        }
        writePose(ctx.partialTick());
    }

    private void writePose(float partialTick) {
        float xRot = 0F;
        float zRot = 0F;
        if (blockEntity.isRinging()) {
            float ringTime = blockEntity.getRingingTicks() + partialTick;
            float angle = (float) (Math.sin(ringTime / Math.PI) / (4.0F + ringTime / 3.0F));
            EnumFacing facing = blockEntity.getRingFacing();
            if (facing != null) {
                switch (facing) {
                    case NORTH -> xRot = -angle;
                    case SOUTH -> xRot = angle;
                    case EAST -> zRot = -angle;
                    case WEST -> zRot = angle;
                    default -> {
                    }
                }
            }
        }

        if (xRot != lastXRot || zRot != lastZRot) {
            instances.xRot(xRot);
            instances.zRot(zRot);
            lastXRot = xRot;
            lastZRot = zRot;
            instances.updateInstancesStatic(initialPose);
        }
    }

    @Override
    public void update(float partialTick) {
    }

    @Override
    protected void _delete() {
        instances.delete();
    }

    @Override
    public void updateLight(float partialTick) {
        packedLight = computePackedLight();
        instances.traverse(inst -> inst.light(packedLight).setChanged());
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        instances.traverse(consumer);
    }
}
