package dev.engine_room.flywheel.impl.extension;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

public interface BlockEntityTypeExtension<T extends BlockEntity> {
    @Nullable
    BlockEntityVisualizer<? super T> flw$getVisualizer();

    void flw$setVisualizer(@Nullable BlockEntityVisualizer<? super T> visualizer);
}
