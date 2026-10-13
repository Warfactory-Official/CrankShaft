package dev.engine_room.flywheel.impl.test;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Mirrors {@code minecraft:stained_glass} -- colour marker for Flywheel-rendered stained-glass cubes.
 */
public final class BlockOitDemoStainedGlass extends BlockOitDemoStained {
    #if MC < 26.3
    public static final MapCodec<BlockOitDemoStainedGlass> CODEC = simpleCodec(BlockOitDemoStainedGlass::new);
    #endif

    public BlockOitDemoStainedGlass(BlockBehaviour.Properties properties) {
        super(properties);
    }

    #if MC < 26.3
    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
    #endif
}
