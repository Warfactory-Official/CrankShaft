package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.util.BlockRenderLayer;
import org.jspecify.annotations.Nullable;

public interface BlockMaterialFunction {
    @Nullable
    Material apply(BlockRenderLayer layer, boolean shaded, boolean ambientOcclusion);
}
