package dev.engine_room.flywheel.iris;

import net.irisshaders.iris.Iris;

final class IrisGate {
    private IrisGate() {
    }

    static boolean isPackInUse() {
        return Iris.isPackInUseQuick();
    }
}
