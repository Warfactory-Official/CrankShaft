package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.golem.CopperGolemOxidationLevels;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.CopperGolemStatueBlockEntity;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public class CopperGolemStatueVisual extends AbstractBlockEntityVisual<CopperGolemStatueBlockEntity> {
    private static final Map<CopperGolemStatueBlock.Pose, ModelLayerLocation> POSE_LAYERS = new EnumMap<>(
            CopperGolemStatueBlock.Pose.class);
    private static final Map<WeatheringCopper.WeatherState, Material> MATERIALS = new EnumMap<>(
            WeatheringCopper.WeatherState.class);

    static {
        POSE_LAYERS.put(CopperGolemStatueBlock.Pose.STANDING, ModelLayers.COPPER_GOLEM);
        POSE_LAYERS.put(CopperGolemStatueBlock.Pose.RUNNING, ModelLayers.COPPER_GOLEM_RUNNING);
        POSE_LAYERS.put(CopperGolemStatueBlock.Pose.SITTING, ModelLayers.COPPER_GOLEM_SITTING);
        POSE_LAYERS.put(CopperGolemStatueBlock.Pose.STAR, ModelLayers.COPPER_GOLEM_STAR);
    }

    private final InstanceTree instances;

    public CopperGolemStatueVisual(VisualizationContext ctx, CopperGolemStatueBlockEntity blockEntity,
                                   float partialTick) {
        super(ctx, blockEntity, partialTick);

        CopperGolemStatueBlock.Pose pose = blockState.getValue(CopperGolemStatueBlock.POSE);
        WeatheringCopper.WeatherState oxidation = blockState.getBlock() instanceof CopperGolemStatueBlock block
                ? block.getWeatheringState()
                : WeatheringCopper.WeatherState.UNAFFECTED;

        instances = InstanceTree.create(instancerProvider(),
                ModelTrees.of(POSE_LAYERS.get(pose), materialFor(oxidation)));
        instances.updateInstancesStatic(createInitialPose());
    }

    private static Material materialFor(WeatheringCopper.WeatherState state) {
        return MATERIALS.computeIfAbsent(state, s -> {
            Identifier texture = CopperGolemOxidationLevels.getOxidationLevel(s).texture();
            return SimpleMaterial.builder()
                                 .texture(texture)
                                 .mipmap(false)
                                 .build();
        });
    }

    private Matrix4fc createInitialPose() {
        BlockPos visualPos = getVisualPosition();
        Direction facing = blockState.getValue(CopperGolemStatueBlock.FACING);
        return new Matrix4f()
                .translate(visualPos.getX(), visualPos.getY(), visualPos.getZ())
                .translate(0.5F, 0.0F, 0.5F)
                .rotateY(-facing.getOpposite().toYRot() * Mth.DEG_TO_RAD);
    }

    @Override
    public void updateLight(float partialTick) {
        int packedLight = computePackedLight();
        instances.traverse(instance -> {
            instance.light(packedLight)
                    .setChanged();
        });
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        instances.traverse(consumer);
    }

    @Override
    protected void _delete() {
        instances.delete();
    }
}
