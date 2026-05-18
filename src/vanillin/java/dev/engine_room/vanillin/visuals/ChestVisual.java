package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.backend.engine.SectionPos;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.model.part.PartPose;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.BlockChest;
import net.minecraft.client.model.ModelChest;
import net.minecraft.client.model.ModelLargeChest;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class ChestVisual extends AbstractChestVisual<TileEntityChest> {

    private static final ResourceLocation NORMAL_TEXTURE = new ResourceLocation("textures/entity/chest/normal.png");
    private static final ResourceLocation TRAPPED_TEXTURE = new ResourceLocation("textures/entity/chest/trapped.png");
    private static final ResourceLocation NORMAL_DOUBLE_TEXTURE = new ResourceLocation("textures/entity/chest/normal_double.png");
    private static final ResourceLocation TRAPPED_DOUBLE_TEXTURE = new ResourceLocation("textures/entity/chest/trapped_double.png");

    private static final ConcurrentMap<ResourceLocation, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();

    private enum PairState { SINGLE, LEFT_PAIR_X, LEFT_PAIR_Z, RIGHT_PAIR }

    private static Material material(ResourceLocation texture) {
        Material cached = MATERIAL_CACHE.get(texture);
        if (cached != null) return cached;
        return MATERIAL_CACHE.computeIfAbsent(texture, t ->
                SimpleMaterial.builderOf(Materials.SOLID_BLOCK).texture(t).mipmap(false).build());
    }

    // 1.12.2's ModelChest has three sibling ModelRenderers; splice them under a synthetic root.
    private static ModelTree buildChestTree(ResourceLocation texture, boolean large) {
        Material mat = material(texture);
        String prefix = (large ? "chest_large:" : "chest_single:") + texture;
        ModelTree base = ModelTrees.of(prefix + ":base",
                () -> (large ? new ModelLargeChest() : new ModelChest()).chestBelow, mat);
        ModelTree lid = ModelTrees.of(prefix + ":lid",
                () -> (large ? new ModelLargeChest() : new ModelChest()).chestLid, mat);
        ModelTree knob = ModelTrees.of(prefix + ":knob",
                () -> (large ? new ModelLargeChest() : new ModelChest()).chestKnob, mat);
        return new ModelTree(null, PartPose.ZERO, new ModelTree[]{base, lid, knob});
    }

    private PairState pairState;
    @Nullable
    private BlockPos partnerPos;

    public ChestVisual(VisualizationContext ctx, TileEntityChest te, float partialTick) {
        super(ctx, te, partialTick);
        pairState = computeState(te);
        buildInstancesFor(pairState);
        writePose(partialTick);
    }

    private static PairState computeState(TileEntityChest te) {
        if (te.adjacentChestZNeg != null || te.adjacentChestXNeg != null) {
            return PairState.RIGHT_PAIR;
        }
        if (te.adjacentChestXPos != null) return PairState.LEFT_PAIR_X;
        if (te.adjacentChestZPos != null) return PairState.LEFT_PAIR_Z;
        return PairState.SINGLE;
    }

    @Nullable
    private BlockPos computePartnerPos() {
        TileEntityChest te = blockEntity;
        return switch (pairState) {
            case LEFT_PAIR_X -> te.adjacentChestXPos != null ? te.adjacentChestXPos.getPos() : null;
            case LEFT_PAIR_Z -> te.adjacentChestZPos != null ? te.adjacentChestZPos.getPos() : null;
            case SINGLE, RIGHT_PAIR -> null;
        };
    }

    private void buildInstancesFor(PairState state) {
        boolean trapped = blockEntity.getChestType() == BlockChest.Type.TRAP;
        boolean large = state == PairState.LEFT_PAIR_X || state == PairState.LEFT_PAIR_Z;
        ResourceLocation texture;
        if (large) {
            texture = trapped ? TRAPPED_DOUBLE_TEXTURE : NORMAL_DOUBLE_TEXTURE;
        } else {
            texture = trapped ? TRAPPED_TEXTURE : NORMAL_TEXTURE;
        }

        partnerPos = computePartnerPos();
        packedLight = computeLight();

        instances = InstanceTree.create(instancerProvider(), buildChestTree(texture, large));
        lid = instances.child(1);
        knob = instances.child(2);

        instances.traverse(inst -> {
            inst.light(packedLight);
            inst.overlay(OverlayTexture.NO_OVERLAY);
        });

        initialPose = buildInitialPose();
        lastLidAngle = Float.NaN;

        if (state == PairState.RIGHT_PAIR) {
            instances.visible(false);
        }

        // Re-register sections so the partner's section also fires updateLight after pair join.
        if (lightSections != null) {
            lightSections.sections(computeSectionSet());
        }
    }

    @Override
    public void setSectionCollector(SectionCollector sectionCollector) {
        this.lightSections = sectionCollector;
        lightSections.sections(computeSectionSet());
    }

    private LongSet computeSectionSet() {
        long ownSection = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (partnerPos == null) {
            LongSet set = new LongArraySet(1);
            set.add(ownSection);
            return set;
        }
        long partnerSection = SectionPos.asLong(partnerPos.getX() >> 4, partnerPos.getY() >> 4, partnerPos.getZ() >> 4);
        if (ownSection == partnerSection) {
            LongSet set = new LongArraySet(1);
            set.add(ownSection);
            return set;
        }
        LongSet set = new LongArraySet(2);
        set.add(ownSection);
        set.add(partnerSection);
        return set;
    }

    // Per-channel max of own + partner light, matching upstream's BrightnessCombiner.
    @Override
    protected int computeLight() {
        int own = computePackedLight();
        if (partnerPos == null) {
            return own;
        }
        int partner = computePackedLight(partnerPos);
        int ownBlock = own & 0xFFFF;
        int ownSky = (own >> 16) & 0xFFFF;
        int partnerBlock = partner & 0xFFFF;
        int partnerSky = (partner >> 16) & 0xFFFF;
        return Math.max(ownBlock, partnerBlock) | (Math.max(ownSky, partnerSky) << 16);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (doDistanceLimitThisFrame(ctx)) {
            return;
        }
        PairState state = computeState(blockEntity);
        if (state != pairState) {
            instances.delete();
            pairState = state;
            buildInstancesFor(state);
        }
        writePose(ctx.partialTick());
    }

    @Override
    protected void writePose(float partialTick) {
        if (pairState == PairState.RIGHT_PAIR) {
            return;
        }
        super.writePose(partialTick);
    }

    @Override
    protected float computeLidAngle(float partialTick) {
        TileEntityChest te = blockEntity;
        float f = te.prevLidAngle + (te.lidAngle - te.prevLidAngle) * partialTick;
        if (te.adjacentChestZNeg != null) {
            f = Math.max(f, te.adjacentChestZNeg.prevLidAngle
                    + (te.adjacentChestZNeg.lidAngle - te.adjacentChestZNeg.prevLidAngle) * partialTick);
        }
        if (te.adjacentChestXNeg != null) {
            f = Math.max(f, te.adjacentChestXNeg.prevLidAngle
                    + (te.adjacentChestXNeg.lidAngle - te.adjacentChestXNeg.prevLidAngle) * partialTick);
        }
        if (te.adjacentChestZPos != null) {
            f = Math.max(f, te.adjacentChestZPos.prevLidAngle
                    + (te.adjacentChestZPos.lidAngle - te.adjacentChestZPos.prevLidAngle) * partialTick);
        }
        if (te.adjacentChestXPos != null) {
            f = Math.max(f, te.adjacentChestXPos.prevLidAngle
                    + (te.adjacentChestXPos.lidAngle - te.adjacentChestXPos.prevLidAngle) * partialTick);
        }
        return ease(f);
    }

    private Matrix4f buildInitialPose() {
        var origin = visualizationContext.renderOrigin();
        float vx = pos.getX() - origin.getX();
        float vy = pos.getY() - origin.getY();
        float vz = pos.getZ() - origin.getZ();
        Matrix4f m = new Matrix4f()
                .translate(vx, vy + 1F, vz + 1F)
                .scale(1F, -1F, -1F)
                .translate(0.5F, 0.5F, 0.5F);
        // Pair offset must precede the Y rotation so the shift stays on the world axis.
        int meta = blockEntity.getBlockMetadata();
        if (meta == 2 && pairState == PairState.LEFT_PAIR_X) {
            m.translate(1F, 0F, 0F);
        } else if (meta == 5 && pairState == PairState.LEFT_PAIR_Z) {
            m.translate(0F, 0F, -1F);
        }
        m.rotateY((float) Math.toRadians(yawForMeta(meta)))
                .translate(-0.5F, -0.5F, -0.5F);
        return m;
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        if (pairState == PairState.RIGHT_PAIR) {
            return;
        }
        super.collectCrumblingInstances(consumer);
    }
}
