package dev.engine_room.flywheel.backend.engine.shaderpack;

import javax.annotation.Nullable;

import java.util.List;

record DeferredReplayPlan(Stage[] stages, @Nullable DeferredReplayPass after, Storage storage,
                          int tileRadius, DeferredReplayResources resources) {
    DeferredReplayPlan {
        stages = stages.clone();
        if (stages.length == 0 || tileRadius < 0) throw new IllegalArgumentException("Empty or invalid replay plan");
        for (var index = 0; index < stages.length; index++) {
            for (var previous = 0; previous < index; previous++) {
                if (stages[index].pass == stages[previous].pass)
                    throw new IllegalArgumentException("Repeated replay pass");
            }
            if (stages[index].pass == after) throw new IllegalArgumentException("Replay exit is inside its stages");
        }
    }

    void validatePasses(List<? extends DeferredReplayPass> nativePasses) {
        var previous = -1;
        for (var stage : stages) {
            var found = -1;
            for (var index = previous + 1; index < nativePasses.size(); index++) {
                if (nativePasses.get(index) == stage.pass) {
                    found = index;
                    break;
                }
            }
            if (found < 0)
                throw new IllegalArgumentException("Replay stages must follow their native renderer's order");
            previous = found;
        }
        if (after != null) {
            for (var index = previous + 1; index < nativePasses.size(); index++)
                if (nativePasses.get(index) == after) return;
            throw new IllegalArgumentException("Replay exit must follow its last stage");
        }
    }

    record Stage(DeferredReplayPass pass, boolean tileMasked) {
    }

    record Storage(int nodesBinding, int countsBinding, int tilesBinding, int commandsBinding,
                   int nodeBytes, int minNodes, int nodeGranule, int maxNodesPerPixel, int tileShift) {
        Storage {
            if (nodesBinding < 0 || countsBinding < 0 || tilesBinding < 0 || commandsBinding < 0
                    || nodesBinding == countsBinding || nodesBinding == tilesBinding || nodesBinding == commandsBinding
                    || countsBinding == tilesBinding || countsBinding == commandsBinding || tilesBinding == commandsBinding
                    || nodeBytes <= 0 || nodeBytes % 16 != 0 || minNodes <= 0 || nodeGranule <= 0
                    || maxNodesPerPixel <= 0 || tileShift < 0 || tileShift > 8) {
                throw new IllegalArgumentException("Invalid deferred replay storage ABI");
            }
        }
    }
}
