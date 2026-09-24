package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.api.visualization.ItemStackVisualizer;
import dev.engine_room.flywheel.impl.extension.ItemExtension;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Item.class)
abstract class ItemMixin implements ItemExtension {
    @Unique
    @Nullable
    private ItemStackVisualizer flw$visualizer;

    @Override
    @Nullable
    public ItemStackVisualizer flw$getVisualizer() {
        return flw$visualizer;
    }

    @Override
    public void flw$setVisualizer(@Nullable ItemStackVisualizer visualizer) {
        flw$visualizer = visualizer;
    }
}
