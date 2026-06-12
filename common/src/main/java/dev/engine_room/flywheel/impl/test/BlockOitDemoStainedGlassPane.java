package dev.engine_room.flywheel.impl.test;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class BlockOitDemoStainedGlassPane extends BlockOitDemoStained {
    public static final MapCodec<BlockOitDemoStainedGlassPane> CODEC = simpleCodec(BlockOitDemoStainedGlassPane::new);

    public BlockOitDemoStainedGlassPane(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
