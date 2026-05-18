package io.github.opencubicchunks.cubicchunks.api.world;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.jspecify.annotations.Nullable;

public interface ICube {
    @Nullable
    ExtendedBlockStorage getStorage();
}
