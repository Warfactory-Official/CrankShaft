package dev.engine_room.flywheel.backend.engine.shaderpack;

interface DeferredReplayTargets {
    int width();

    int height();

    int depth();

    int opaqueDepth();

    Target get(int index);

    default int image(String name) {
        throw new UnsupportedOperationException("Custom replay images are unavailable");
    }

    default int frame() {
        throw new UnsupportedOperationException("Replay frame identity is unavailable");
    }

    interface Target {
        int main();

        int alt();

        int format();
    }
}
