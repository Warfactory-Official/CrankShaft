package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.model.part.PartPose;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.client.model.ModelShulker;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;

import java.util.function.Consumer;

public class ShulkerBoxVisual extends AbstractBlockEntityVisual<TileEntityShulkerBox>
        implements SimpleDynamicVisual {

    private static final ResourceLocation[] TEXTURES = {
            new ResourceLocation("textures/entity/shulker/shulker_white.png"),
            new ResourceLocation("textures/entity/shulker/shulker_orange.png"),
            new ResourceLocation("textures/entity/shulker/shulker_magenta.png"),
            new ResourceLocation("textures/entity/shulker/shulker_light_blue.png"),
            new ResourceLocation("textures/entity/shulker/shulker_yellow.png"),
            new ResourceLocation("textures/entity/shulker/shulker_lime.png"),
            new ResourceLocation("textures/entity/shulker/shulker_pink.png"),
            new ResourceLocation("textures/entity/shulker/shulker_gray.png"),
            new ResourceLocation("textures/entity/shulker/shulker_silver.png"),
            new ResourceLocation("textures/entity/shulker/shulker_cyan.png"),
            new ResourceLocation("textures/entity/shulker/shulker_purple.png"),
            new ResourceLocation("textures/entity/shulker/shulker_blue.png"),
            new ResourceLocation("textures/entity/shulker/shulker_brown.png"),
            new ResourceLocation("textures/entity/shulker/shulker_green.png"),
            new ResourceLocation("textures/entity/shulker/shulker_red.png"),
            new ResourceLocation("textures/entity/shulker/shulker_black.png"),
    };

    private static final Material[] MATERIALS = new Material[16];
    static {
        for (int i = 0; i < 16; i++) {
            MATERIALS[i] = SimpleMaterial.builder()
                    .cutout(CutoutShaders.ONE_TENTH)
                    .texture(TEXTURES[i])
                    .mipmap(false)
                    .backfaceCulling(false)
                    .build();
        }
    }

    private static final float ROT_270_RAD = 1.5f * (float) Math.PI;
    // ModelShulker.lid base rotation point = (0, 24, 0) → y = 24 * 1/16 = 1.5 world units.
    private static final float LID_BASE_Y = 1.5F;

    private static ModelTree buildTree(int colorIdx) {
        Material mat = MATERIALS[colorIdx];
        String prefix = "shulker:" + colorIdx;
        ModelTree base = ModelTrees.of(prefix + ":base", () -> new ModelShulker().base, mat);
        ModelTree lid = ModelTrees.of(prefix + ":lid", () -> new ModelShulker().lid, mat);
        return new ModelTree(null, PartPose.ZERO, new ModelTree[]{base, lid});
    }

    private final InstanceTree instances;
    private final InstanceTree lid;
    // initialPose + facing are constant for the visual's lifetime — see BellVisual.
    private final Matrix4f initialPose;
    private int packedLight;
    private float lastProgress = Float.NaN;

    public ShulkerBoxVisual(VisualizationContext ctx, TileEntityShulkerBox te, float partialTick) {
        super(ctx, te, partialTick);

        EnumDyeColor color = te.getColor();
        instances = InstanceTree.create(instancerProvider(), buildTree(color.getMetadata()));
        lid = instances.child(1);

        packedLight = computePackedLight();
        instances.traverse(inst -> {
            inst.light(packedLight);
            inst.overlay(OverlayTexture.NO_OVERLAY);
        });

        initialPose = buildInitialPose(readFacing());
        writePose(partialTick);
    }

    private EnumFacing readFacing() {
        if (blockState.getBlock() instanceof BlockShulkerBox) {
            return blockState.getValue(BlockShulkerBox.FACING);
        }
        return EnumFacing.UP;
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (doDistanceLimitThisFrame(ctx)) {
            return;
        }
        writePose(ctx.partialTick());
    }

    private void writePose(float partialTick) {
        float progress = blockEntity.getProgress(partialTick);
        if (progress == lastProgress) {
            return;
        }
        lastProgress = progress;

        lid.yPos(LID_BASE_Y - 0.5F * progress);
        lid.yRot(ROT_270_RAD * progress);

        instances.updateInstancesStatic(initialPose);
    }

    private Matrix4f buildInitialPose(EnumFacing facing) {
        var origin = visualizationContext.renderOrigin();
        float vx = pos.getX() - origin.getX();
        float vy = pos.getY() - origin.getY();
        float vz = pos.getZ() - origin.getZ();
        Matrix4f m = new Matrix4f()
                .translate(vx + 0.5F, vy + 1.5F, vz + 0.5F)
                .scale(1F, -1F, -1F)
                .translate(0F, 1F, 0F)
                .scale(0.9995F)
                .translate(0F, -1F, 0F);
        applyFacing(m, facing);
        return m;
    }

    private static void applyFacing(Matrix4f m, EnumFacing facing) {
        switch (facing) {
            case DOWN:
                m.translate(0F, 2F, 0F).rotateX((float) Math.PI);
                break;
            case NORTH:
                m.translate(0F, 1F, 1F)
                        .rotateX((float) (Math.PI * 0.5))
                        .rotateZ((float) Math.PI);
                break;
            case SOUTH:
                m.translate(0F, 1F, -1F)
                        .rotateX((float) (Math.PI * 0.5));
                break;
            case WEST:
                m.translate(-1F, 1F, 0F)
                        .rotateX((float) (Math.PI * 0.5))
                        .rotateZ((float) (-Math.PI * 0.5));
                break;
            case EAST:
                m.translate(1F, 1F, 0F)
                        .rotateX((float) (Math.PI * 0.5))
                        .rotateZ((float) (Math.PI * 0.5));
                break;
            case UP:
            default:
                break;
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
