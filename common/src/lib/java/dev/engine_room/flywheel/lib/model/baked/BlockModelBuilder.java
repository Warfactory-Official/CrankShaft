package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;

public final class BlockModelBuilder {
    private BlockModelBuilder() {
    }

    public static Model build(BlockState state) {
        return build(state, 0);
    }

    public static Model build(BlockState state, int cullMask) {
        return build(state, cullMask, state.getSeed(BlockPos.ZERO));
    }

    public static Model build(BlockState state, int cullMask, long seed) {
        return build(state, cullMask, seed, ModelUtil::getMaterial);
    }

    /**
     * Bake a block state's model, choosing each layer's flywheel material via {@code materialFunc}.
     */
    public static Model build(BlockState state, int cullMask, BlockMaterialFunction materialFunc) {
        return build(state, cullMask, state.getSeed(BlockPos.ZERO), materialFunc);
    }

    public static Model build(BlockState state, int cullMask, long seed, BlockMaterialFunction materialFunc) {
        EnumMap<ChunkSectionLayer, BakedMesh> meshes = BakedModelBufferer.INSTANCE.bufferBlock(state, cullMask, seed);
        return ModelUtil.buildModel(meshes, materialFunc);
    }
}
