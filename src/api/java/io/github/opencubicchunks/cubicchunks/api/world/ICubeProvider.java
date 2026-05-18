package io.github.opencubicchunks.cubicchunks.api.world;

import org.jspecify.annotations.Nullable;

public interface ICubeProvider {
    @Nullable
    ICube getLoadedCube(int cubeX, int cubeY, int cubeZ);
}
