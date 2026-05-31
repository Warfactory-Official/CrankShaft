package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.util.BlockRenderLayer;
import org.jspecify.annotations.Nullable;

/**
 * Picks a {@link Material} for a baked block mesh bucket. 1.12.2 analog of upstream's
 * {@code BlockMaterialFunction}; the {@code (RenderType, shaded, ambientOcclusion)} triple
 * collapses to {@code (BlockRenderLayer, shaded)} because 1.12.2 AO is per-block not per-quad.
 *
 * <p>Returning {@code null} skips that bucket entirely (no mesh emitted).
 */
@FunctionalInterface
public interface BlockMaterialFunction {
    @Nullable
    Material apply(BlockRenderLayer layer, boolean shaded);
}
