package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visual.FarVisualState;
import dev.engine_room.flywheel.api.visualization.FarVisualModel;
import dev.engine_room.flywheel.api.visualization.FarVisualProvider;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.FarVisualStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Closed container models shared with the live visuals. Template light starts at zero;
 * the source page supplies the block's light when the representation is instantiated.
 */
public final class ContainerFarVisuals {
    private static final RendererReloadCache<BlockState, FarVisualModel> CHEST_MODELS =
            new RendererReloadCache<>(state -> FarVisualStates.modelTree(
                    ContainerVisualModels.chestModel(state),
                    ContainerVisualModels.chestPose(state, BlockPos.ZERO), FarVisualState.Appearance.lit(0)));
    private static final RendererReloadCache<BlockState, FarVisualModel> SHULKER_MODELS =
            new RendererReloadCache<>(state -> FarVisualStates.modelTree(
                    ContainerVisualModels.shulkerModel(((ShulkerBoxBlock) state.getBlock()).getColor()),
                    ContainerVisualModels.shulkerPose(state, BlockPos.ZERO), FarVisualState.Appearance.lit(0)));

    public static final FarVisualProvider CHEST = CHEST_MODELS::get;
    public static final FarVisualProvider SHULKER_BOX = SHULKER_MODELS::get;

    private ContainerFarVisuals() {
    }
}
