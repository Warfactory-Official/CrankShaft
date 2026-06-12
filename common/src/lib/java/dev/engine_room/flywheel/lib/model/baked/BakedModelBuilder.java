package dev.engine_room.flywheel.lib.model.baked;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;

/**
 * Builds a flywheel {@link Model} from a standalone {@link BlockStateModel} -- typically a {@link PartialModel}'s bake.
 *
 * <p>26.2 bakes tint/shade/AO into the geometry, so upstream's virtual level/pos inputs are gone; what remains is an
 * optional {@link #poseStack(PoseStack) pose} and per-layer {@link #materialFunc(BlockMaterialFunction) material override}.
 */
public final class BakedModelBuilder {
    private final BlockStateModel model;
    @Nullable
    private PoseStack poseStack;
    @Nullable
    private BlockMaterialFunction materialFunc;

    public BakedModelBuilder(BlockStateModel model) {
        this.model = model;
    }

    public BakedModelBuilder poseStack(@Nullable PoseStack poseStack) {
        this.poseStack = poseStack;
        return this;
    }

    public BakedModelBuilder materialFunc(@Nullable BlockMaterialFunction materialFunc) {
        this.materialFunc = materialFunc;
        return this;
    }

    public Model build() {
        BlockMaterialFunction func = materialFunc != null ? materialFunc : ModelUtil::getMaterial;

        EnumMap<ChunkSectionLayer, BakedMesh> meshes = BakedModelBufferer.INSTANCE.bufferModel(model, poseStack);
        return ModelUtil.buildModel(meshes, func);
    }
}
