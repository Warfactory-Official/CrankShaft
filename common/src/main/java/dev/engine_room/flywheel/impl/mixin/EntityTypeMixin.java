package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.impl.extension.EntityTypeExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityType.class)
abstract class EntityTypeMixin<T extends Entity> implements EntityTypeExtension<T> {
    @Unique
    @Nullable
    private EntityVisualizer<? super T> flw$visualizer;

    @Override
    @Nullable
    public EntityVisualizer<? super T> flw$getVisualizer() {
        return flw$visualizer;
    }

    @Override
    public void flw$setVisualizer(@Nullable EntityVisualizer<? super T> visualizer) {
        flw$visualizer = visualizer;
    }
}
