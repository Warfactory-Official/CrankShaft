package dev.engine_room.flywheel.backend.engine.shaderpack;

interface DeferredReplayBuffers {
    int buffer(int binding);

    void replace(int binding, int handle);
}
