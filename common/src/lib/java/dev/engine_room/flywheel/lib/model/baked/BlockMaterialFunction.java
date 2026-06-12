package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/**
 * Picks the flywheel {@link Material} a baked vanilla quad draws with, from the quad's {@link ChunkSectionLayer}.
 * Downstream extension point: pass a custom function to {@link BakedModelBuilder#materialFunc} to override the per-layer material.
 */
@FunctionalInterface
public interface BlockMaterialFunction {
    @Nullable
    Material apply(ChunkSectionLayer layer);
}
