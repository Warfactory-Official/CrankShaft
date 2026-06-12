package dev.engine_room.flywheel.impl.test;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Mirrors {@code minecraft:stained_glass} -- colour marker for Flywheel-rendered stained-glass cubes.
 */
public final class BlockOitDemoStainedGlass extends BlockOitDemoStained {
    public static final MapCodec<BlockOitDemoStainedGlass> CODEC = simpleCodec(BlockOitDemoStainedGlass::new);

    public BlockOitDemoStainedGlass(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
}
