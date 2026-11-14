package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visual.FarVisual;
import dev.engine_room.flywheel.api.visual.FarVisualState;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.part.InstanceTree;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import dev.engine_room.flywheel.lib.model.part.ModelTrees;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.FarVisualStates;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.joml.Matrix4f;

import java.util.Set;
import java.util.function.Consumer;

public class ShulkerBoxVisual extends AbstractBlockEntityVisual<ShulkerBoxBlockEntity> implements SimpleDynamicVisual, FarVisual {
    private static final Material MATERIAL = SimpleMaterial.builder()
                                                           .cutout(CutoutShaders.ONE_TENTH)
                                                           .texture(Sheets.SHULKER_SHEET)
                                                           .mipmap(false)
                                                           .backfaceCulling(false)
                                                           .build();
    private static final Set<String> PATHS_TO_PRUNE = Set.of("/head");

    private final ModelTree model;
    private final InstanceTree instances;
    private final InstanceTree lid;

    private final Matrix4f initialPose;

    private float lastProgress = Float.NaN;
    private int packedLight;

    public ShulkerBoxVisual(VisualizationContext ctx, ShulkerBoxBlockEntity blockEntity, float partialTick) {
        super(ctx, blockEntity, partialTick);

        DyeColor color = blockEntity.getColor();
        SpriteId textureId;
        if (color == null) {
            textureId = Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION;
        } else {
            textureId = Sheets.getShulkerBoxSprite(color);
        }
        TextureAtlasSprite texture = Minecraft.getInstance()
                                              .getAtlasManager()
                                              .get(textureId);

        model = ModelTrees.of(ModelLayers.SHULKER, PATHS_TO_PRUNE, texture, MATERIAL);
        instances = InstanceTree.create(instancerProvider(), model);
        lid = instances.childOrThrow("lid");

        initialPose = ContainerVisualModels.shulkerPose(blockState, getVisualPosition());
        applyTransform(partialTick);
    }


    @Override
    public void beginFrame(Context context) {
        if (doDistanceLimitThisFrame(context) || !isVisible(context.frustum())) {
            return;
        }

        applyTransform(context.partialTick());
    }

    private void applyTransform(float partialTicks) {
        float progress = blockEntity.getProgress(partialTicks);
        if (progress == lastProgress) {
            return;
        }
        lastProgress = progress;

        lid.yRot(1.5f * Mth.PI * progress);
        lid.yPos(24f - progress * 8f);

        instances.updateInstancesStatic(initialPose);
    }

    @Override
    public void updateLight(float _) {
        packedLight = computePackedLight();
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
    public FarVisualState retainedState() {
        return FarVisualStates.modelTree(visualizationContext, model, initialPose,
                FarVisualState.Appearance.lit(packedLight));
    }

    @Override
    protected void _delete() {
        instances.delete();
    }
}
