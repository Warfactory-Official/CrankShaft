package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.BlockModels;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.model.part.PartPose;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.model.ModelMinecart;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public class MinecartVisual<T extends EntityMinecart> extends AbstractEntityVisual<T>
        implements SimpleTickableVisual, SimpleDynamicVisual {

    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/minecart.png");
    private static final Material MATERIAL = SimpleMaterial.builderOf(Materials.SOLID_BLOCK)
            .texture(TEXTURE)
            .mipmap(false)
            .build();

    // RenderMinecart sets sideModels[5].rotationPointY = 4 - ageInTicks (-0.1F) every frame,
    // so the inner floor panel sits 0.1*0.0625 higher than its constructor-set rotationPoint.
    private static final float INNER_PANEL_RP_Y = 4.1F;

    private static ModelTree buildTree() {
        ModelTree[] children = new ModelTree[6];
        for (int i = 0; i < 6; i++) {
            int idx = i;
            children[i] = ModelTrees.of("minecart:" + idx, () -> {
                ModelMinecart m = new ModelMinecart();
                if (idx == 5) {
                    m.sideModels[5].rotationPointY = INNER_PANEL_RP_Y;
                }
                return m.sideModels[idx];
            }, MATERIAL);
        }
        return new ModelTree(null, PartPose.ZERO, children);
    }

    private final InstanceTree instances;
    private final Matrix4f cartFrame = new Matrix4f();
    private final Matrix4f bodyFrame = new Matrix4f();
    private final Matrix4f cargoMatrix = new Matrix4f();
    @Nullable
    private TransformedInstance cargoInstance;
    @Nullable
    private IBlockState cargoState;

    public MinecartVisual(VisualizationContext ctx, T entity, float partialTick) {
        super(ctx, entity, partialTick);

        instances = InstanceTree.create(instancerProvider(), buildTree());
        // 1.12.2 stores overlay per-instance, so seed it once at construction.
        instances.traverse(inst -> inst.overlay(OverlayTexture.NO_OVERLAY));

        syncCargoState();
        updateInstances(partialTick);
    }

    @Override
    public void tick(TickableVisual.Context context) {
        syncCargoState();
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (!isVisible(ctx.frustum())) {
            return;
        }
        updateInstances(ctx.partialTick());
    }

    private void updateInstances(float partialTick) {
        double posX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTick;
        double posY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTick;
        double posZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTick;

        var renderOrigin = renderOrigin();
        float vx = (float) (posX - renderOrigin.getX());
        float vy = (float) (posY - renderOrigin.getY());
        float vz = (float) (posZ - renderOrigin.getZ());

        long randomBits = (long) entity.getEntityId() * 493286711L;
        randomBits = randomBits * randomBits * 4392167121L + randomBits * 98761L;
        float nudgeX = (((float) (randomBits >> 16 & 7L) + 0.5f) / 8.0f - 0.5F) * 0.004f;
        float nudgeY = (((float) (randomBits >> 20 & 7L) + 0.5f) / 8.0f - 0.5F) * 0.004f;
        float nudgeZ = (((float) (randomBits >> 24 & 7L) + 0.5f) / 8.0f - 0.5F) * 0.004f;

        float yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTick;
        float pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTick;
        Vec3d railPos = entity.getPos(posX, posY, posZ);
        float railOffsetX = 0F;
        float railOffsetY = 0F;
        float railOffsetZ = 0F;
        if (railPos != null) {
            Vec3d offset1 = entity.getPosOffset(posX, posY, posZ, 0.3D);
            Vec3d offset2 = entity.getPosOffset(posX, posY, posZ, -0.3D);
            if (offset1 == null) offset1 = railPos;
            if (offset2 == null) offset2 = railPos;
            railOffsetX = (float) (railPos.x - posX);
            railOffsetY = (float) ((offset1.y + offset2.y) / 2.0D - posY);
            railOffsetZ = (float) (railPos.z - posZ);
            Vec3d vec = offset2.add(-offset1.x, -offset1.y, -offset1.z);
            if (vec.lengthSquared() != 0.0D) {
                vec = vec.normalize();
                yaw = (float) (Math.atan2(vec.z, vec.x) * 180.0D / Math.PI);
                pitch = (float) (Math.atan(vec.y) * 73.0D);
            }
        }

        cartFrame.identity()
                .translate(nudgeX, nudgeY, nudgeZ)
                .translate(vx + railOffsetX, vy + railOffsetY + 0.375F, vz + railOffsetZ)
                .rotateY((float) Math.toRadians(180.0F - yaw))
                .rotateZ((float) Math.toRadians(-pitch));

        float rollingAmp = (float) entity.getRollingAmplitude() - partialTick;
        float damage = entity.getDamage() - partialTick;
        if (damage < 0F) damage = 0F;
        if (rollingAmp > 0F) {
            float shakeDeg = MathHelper.sin(rollingAmp) * rollingAmp * damage / 10.0F
                    * (float) entity.getRollingDirection();
            cartFrame.rotateX((float) Math.toRadians(shakeDeg));
        }

        // Apply cargo BEFORE the (-1,-1,1) body scale that RenderMinecart uses.
        int packedLight = computePackedLight(partialTick);
        updateCargoTransform(packedLight, partialTick);

        bodyFrame.set(cartFrame).scale(-1F, -1F, 1F);
        instances.updateInstances(bodyFrame);
        updateLight(packedLight);
    }

    private void updateLight(int packedLight) {
        instances.traverse(inst -> inst.light(packedLight).setChanged());
    }

    private void syncCargoState() {
        IBlockState state = entity.getDisplayTile();
        EnumBlockRenderType renderType = state.getRenderType();
        if (state.getBlock() == Blocks.AIR || renderType == EnumBlockRenderType.INVISIBLE) {
            if (cargoInstance != null) {
                cargoInstance.delete();
                cargoInstance = null;
                cargoState = null;
            }
            return;
        }
        if (cargoInstance != null && state == cargoState) {
            return;
        }
        if (cargoInstance != null) {
            cargoInstance.delete();
        }
        Model model = BlockModels.get(state);
        Instancer<TransformedInstance> instancer = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, model);
        cargoInstance = instancer.createInstance();
        cargoInstance.overlay(OverlayTexture.NO_OVERLAY);
        cargoState = state;
    }

    private void updateCargoTransform(int packedLight, float partialTick) {
        if (cargoInstance == null) {
            return;
        }
        cargoMatrix.set(cartFrame)
                .scale(0.75F)
                .translate(-0.5F, (entity.getDisplayTileOffset() - 8) / 16F, 0.5F)
                .rotateY((float) Math.toRadians(90.0));
        updateContents(cargoInstance, cargoMatrix, partialTick, packedLight);
        cargoInstance.setTransform(cargoMatrix);
        cargoInstance.light(packedLight);
        cargoInstance.setChanged();
    }

    protected void updateContents(TransformedInstance contents, Matrix4f pose, float partialTick, int light) {
    }

    public static boolean shouldSkipRender(EntityMinecart minecart) {
        return minecart.getDisplayTile().getRenderType()
                != EnumBlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected void _delete() {
        instances.delete();
        if (cargoInstance != null) {
            cargoInstance.delete();
            cargoInstance = null;
        }
    }
}
