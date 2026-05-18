package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.model.part.PartPose;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import net.minecraft.client.model.ModelChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

/**
 * CS-only — {@code TileEntityEnderChest} doesn't extend {@code TileEntityChest}, so it can't reuse
 * {@link ChestVisual}. Geometry mirrors single-chest; no pair logic.
 */
public final class EnderChestVisual extends AbstractChestVisual<TileEntityEnderChest> {

    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/chest/ender.png");
    private static final Material MATERIAL = SimpleMaterial.builderOf(Materials.SOLID_BLOCK)
            .texture(TEXTURE)
            .mipmap(false)
            .build();

    private static ModelTree buildTree() {
        String prefix = "enderchest";
        ModelTree base = ModelTrees.of(prefix + ":base", () -> new ModelChest().chestBelow, MATERIAL);
        ModelTree lid = ModelTrees.of(prefix + ":lid", () -> new ModelChest().chestLid, MATERIAL);
        ModelTree knob = ModelTrees.of(prefix + ":knob", () -> new ModelChest().chestKnob, MATERIAL);
        return new ModelTree(null, PartPose.ZERO, new ModelTree[]{base, lid, knob});
    }

    public EnderChestVisual(VisualizationContext ctx, TileEntityEnderChest te, float partialTick) {
        super(ctx, te, partialTick);

        instances = InstanceTree.create(instancerProvider(), buildTree());
        lid = instances.child(1);
        knob = instances.child(2);

        packedLight = computePackedLight();
        instances.traverse(inst -> {
            inst.light(packedLight);
            inst.overlay(OverlayTexture.NO_OVERLAY);
        });

        initialPose = buildInitialPose();
        writePose(partialTick);
    }

    @Override
    protected float computeLidAngle(float partialTick) {
        float f = blockEntity.prevLidAngle + (blockEntity.lidAngle - blockEntity.prevLidAngle) * partialTick;
        return ease(f);
    }

    private Matrix4f buildInitialPose() {
        var origin = visualizationContext.renderOrigin();
        float vx = pos.getX() - origin.getX();
        float vy = pos.getY() - origin.getY();
        float vz = pos.getZ() - origin.getZ();
        return new Matrix4f()
                .translate(vx, vy + 1F, vz + 1F)
                .scale(1F, -1F, -1F)
                .translate(0.5F, 0.5F, 0.5F)
                .rotateY((float) Math.toRadians(yawForMeta(blockEntity.getBlockMetadata())))
                .translate(-0.5F, -0.5F, -0.5F);
    }
}
