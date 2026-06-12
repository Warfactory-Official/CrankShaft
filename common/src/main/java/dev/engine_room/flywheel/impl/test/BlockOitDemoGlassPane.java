package dev.engine_room.flywheel.impl.test;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class BlockOitDemoGlassPane extends BlockOitDemoGlass {
    public static final MapCodec<BlockOitDemoGlassPane> CODEC = simpleCodec(BlockOitDemoGlassPane::new);

    public BlockOitDemoGlassPane(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
