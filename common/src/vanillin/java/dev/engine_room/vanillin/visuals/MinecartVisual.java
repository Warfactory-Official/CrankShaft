package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public class MinecartVisual<T extends AbstractMinecart> extends AbstractEntityVisual<T> implements SimpleTickableVisual, SimpleDynamicVisual {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/minecart/minecart.png");
    private static final Material MATERIAL = SimpleMaterial.builder()
                                                           .texture(TEXTURE)
                                                           .mipmap(false)
                                                           .build();

    private final InstanceTree instances;
    private final Matrix4fStack stack = new Matrix4fStack(2);
    @Nullable
    private TransformedInstance contents;
    private BlockState blockState;

    public MinecartVisual(VisualizationContext ctx, T entity, float partialTick, ModelLayerLocation layerLocation) {
        super(ctx, entity, partialTick);

        instances = InstanceTree.create(instancerProvider(), ModelTrees.of(layerLocation, MATERIAL));
        blockState = entity.getDisplayBlockState();
        contents = createContentsInstance();

        updateInstances(partialTick);
        updateLight(partialTick);
    }

    @Nullable
    private TransformedInstance createContentsInstance() {
        if (blockState.getRenderShape() == RenderShape.INVISIBLE) {
            return null;
        }

        return instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.block(blockState))
                                  .createInstance();
    }

    @Override
    public void tick(TickableVisual.Context context) {
        BlockState displayBlockState = entity.getDisplayBlockState();

        if (displayBlockState != blockState) {
            blockState = displayBlockState;
            if (contents != null) {
                contents.delete();
            }
            contents = createContentsInstance();
        }
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        if (!isVisible(context.frustum())) {
            return;
        }

        updateInstances(context.partialTick());
    }

    private void updateInstances(float partialTick) {
        stack.identity();

        MinecartBehavior behavior = entity.getBehavior();
        boolean newRender = behavior instanceof NewMinecartBehavior;

        double entityX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double entityY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double entityZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        float xRot;
        float yRot;
        Vec3 renderPos = null;
        if (behavior instanceof NewMinecartBehavior newBehavior && newBehavior.cartHasPosRotLerp()) {
            renderPos = newBehavior.getCartLerpPosition(partialTick);
            xRot = newBehavior.getCartLerpXRot(partialTick);
            yRot = newBehavior.getCartLerpYRot(partialTick);
        } else if (newRender) {
            xRot = entity.getXRot();
            yRot = entity.getYRot();
        } else {
            xRot = entity.getXRot(partialTick);
            yRot = entity.getYRot(partialTick);
        }

        double baseX = renderPos != null ? renderPos.x : entityX;
        double baseY = renderPos != null ? renderPos.y : entityY;
        double baseZ = renderPos != null ? renderPos.z : entityZ;

        var renderOrigin = renderOrigin();
        stack.translate((float) (baseX - renderOrigin.getX()), (float) (baseY - renderOrigin.getY()),
                (float) (baseZ - renderOrigin.getZ()));

        long randomBits = entity.getId() * 493286711L;
        randomBits = randomBits * randomBits * 4392167121L + randomBits * 98761L;
        float nudgeX = (((float) (randomBits >> 16 & 7L) + 0.5f) / 8.0f - 0.5F) * 0.004f;
        float nudgeY = (((float) (randomBits >> 20 & 7L) + 0.5f) / 8.0f - 0.5F) * 0.004f;
        float nudgeZ = (((float) (randomBits >> 24 & 7L) + 0.5f) / 8.0f - 0.5F) * 0.004f;
        stack.translate(nudgeX, nudgeY, nudgeZ);

        if (newRender) {
            stack.rotateY(yRot * Mth.DEG_TO_RAD);
            stack.rotateZ(-xRot * Mth.DEG_TO_RAD);
            stack.translate(0.0F, 0.375F, 0.0F);
        } else {
            float rotation = yRot;
            float pitch = xRot;
            if (behavior instanceof OldMinecartBehavior oldBehavior) {
                Vec3 pos = oldBehavior.getPos(entityX, entityY, entityZ);
                if (pos != null) {
                    Vec3 offset1 = Objects.requireNonNullElse(oldBehavior.getPosOffs(entityX, entityY, entityZ, 0.3D),
                            pos);
                    Vec3 offset2 = Objects.requireNonNullElse(oldBehavior.getPosOffs(entityX, entityY, entityZ, -0.3D),
                            pos);

                    stack.translate((float) (pos.x - entityX), (float) ((offset1.y + offset2.y) / 2.0D - entityY),
                            (float) (pos.z - entityZ));
                    Vec3 direction = offset2.add(-offset1.x, -offset1.y, -offset1.z);
                    if (direction.length() != 0.0D) {
                        direction = direction.normalize();
                        rotation = (float) (Math.atan2(direction.z, direction.x) * 180.0D / Math.PI);
                        pitch = (float) (Math.atan(direction.y) * 73.0D);
                    }
                }
            }

            stack.translate(0.0F, 0.375F, 0.0F);
            stack.rotateY((180.0F - rotation) * Mth.DEG_TO_RAD);
            stack.rotateZ(-pitch * Mth.DEG_TO_RAD);
        }

        float hurtTime = entity.getHurtTime() - partialTick;
        float damage = Math.max(entity.getDamage() - partialTick, 0.0F);
        if (hurtTime > 0) {
            stack.rotateX(
                    (Mth.sin(hurtTime) * hurtTime * damage / 10.0F * (float) entity.getHurtDir()) * Mth.DEG_TO_RAD);
        }

        if (contents != null) {
            int displayOffset = entity.getDisplayOffset();
            stack.pushMatrix();
            stack.scale(0.75F, 0.75F, 0.75F);
            stack.translate(-0.5F, (float) (displayOffset - 8) / 16, 0.5F);
            stack.rotateY(90 * Mth.DEG_TO_RAD);
            updateContents(contents, stack, partialTick);
            stack.popMatrix();
        }

        stack.scale(-1.0F, -1.0F, 1.0F);
        instances.updateInstances(stack);

        // TODO: Use LightUpdatedVisual/ShaderLightVisual if possible.
        updateLight(partialTick);
    }

    protected void updateContents(TransformedInstance contents, Matrix4f pose, float partialTick) {
        contents.setTransform(pose)
                .setChanged();
    }

    public void updateLight(float partialTick) {
        int packedLight = computePackedLight(partialTick);
        instances.traverse(instance -> {
            instance.light(packedLight)
                    .setChanged();
        });
        FlatLit.relight(packedLight, contents);
    }

    @Override
    protected void _delete() {
        instances.delete();
        if (contents != null) {
            contents.delete();
        }
    }
}
