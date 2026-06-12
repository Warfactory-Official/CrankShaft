package dev.engine_room.flywheel.impl.test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public final class TileEntityOitDemo extends BlockEntity {
    public TileEntityOitDemo(BlockPos pos, BlockState state) {
        super(Objects.requireNonNull(OitDemoContent.OIT_DEMO_BE), pos, state);
    }
}
