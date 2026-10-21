package dev.engine_room.flywheel.backend.engine.shaderpack;

import java.util.Set;

interface DeferredReplayPass {
    String name();

    int program();

    Compute[] computes();

    int width();

    int height();

    int[] drawBuffers();

    Set<Integer> readAlt();

    int outputTexture(int slot);

    void execute();

    interface Compute {
        int program();

        int groupsX();

        int groupsY();

        int groupsZ();

        default boolean indirect() {
            return false;
        }

        default int indirectBuffer() {
            return 0;
        }

        default long indirectOffset() {
            return 0;
        }
    }
}
