package dev.engine_room.flywheel.impl.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkRenderList.class)
public interface ChunkRenderListAccessor {
    @Accessor("sectionsWithGeometryMap")
    long[] flywheel$sectionsWithGeometryMap();
}
