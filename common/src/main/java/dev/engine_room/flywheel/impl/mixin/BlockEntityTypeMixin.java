package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.impl.extension.BlockEntityTypeExtension;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockEntityType.class)
abstract class BlockEntityTypeMixin<T extends BlockEntity> implements BlockEntityTypeExtension<T> {
    @Unique
    @Nullable
    private BlockEntityVisualizer<? super T> flw$visualizer;

    @Unique
    @Nullable
    private Object flw$sodiumPredicate;

    @Override
    @Nullable
    public BlockEntityVisualizer<? super T> flw$getVisualizer() {
        return flw$visualizer;
    }

    @Override
    public void flw$setVisualizer(@Nullable BlockEntityVisualizer<? super T> visualizer) {
        if (SodiumCompat.ACTIVE) {
            flw$sodiumPredicate = SodiumCompat.onSetBlockEntityVisualizer((BlockEntityType<T>) (Object) this,
                    flw$visualizer, visualizer, flw$sodiumPredicate);
        }

        flw$visualizer = visualizer;
    }
}
