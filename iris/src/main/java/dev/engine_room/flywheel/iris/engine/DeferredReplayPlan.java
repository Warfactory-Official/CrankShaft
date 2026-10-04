package dev.engine_room.flywheel.iris.engine;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A validated pack's replay schedule and storage ABI, built once for an Iris CompositeRenderer on the render
 * thread. Pass identities belong to that renderer. Stages run in their declared order, with attached compute
 * programs before graphics. The first stage is also the native entry point; {@code after} restores borrowed
 * resources before the following native pass, or at renderer completion when absent.
 *
 * <p>Storage counters are four uints: allocated nodes, overflow bits, maximum layers and integration-owned metadata.
 * Producers rebuild the pool each frame. For tile-masked stages, the tile buffer contains undilated counts followed
 * by dilation scratch; otherwise the integration owns its layout. The pack owns node encoding, collection and
 * sorting. A tile-masked stage must cover the full replay viewport.
 * Pack-owned shaders/resources remain responsible for material interpretation and temporal-history isolation.
 */
public record DeferredReplayPlan(Stage[] stages, @Nullable DeferredCompositePass after, Storage storage,
                                 int tileRadius, DeferredReplayResources resources) {
    public DeferredReplayPlan {
        stages = stages.clone();
        if (stages.length == 0 || tileRadius < 0) throw new IllegalArgumentException("Empty or invalid replay plan");
        for (int i = 0; i < stages.length; i++) {
            for (int j = 0; j < i; j++) {
                if (stages[i].pass == stages[j].pass) throw new IllegalArgumentException("Repeated replay pass");
            }
            if (stages[i].pass == after) throw new IllegalArgumentException("Replay exit is inside its stages");
        }
    }

    void validatePasses(List<?> nativePasses) {
        int previous = -1;
        for (Stage stage : stages) {
            int found = -1;
            for (int i = previous + 1; i < nativePasses.size(); i++) {
                if (nativePasses.get(i) == stage.pass) {
                    found = i;
                    break;
                }
            }
            if (found < 0)
                throw new IllegalArgumentException("Replay stages must follow their native renderer's order");
            previous = found;
        }
        if (after != null) {
            for (int i = previous + 1; i < nativePasses.size(); i++) if (nativePasses.get(i) == after) return;
            throw new IllegalArgumentException("Replay exit must follow its last stage");
        }
    }

    public record Stage(DeferredCompositePass pass, boolean tileMasked) {
    }

    public record Storage(int nodesBinding, int countsBinding, int tilesBinding, int commandsBinding,
                          int nodeBytes, int minNodes, int nodeGranule, int maxNodesPerPixel, int tileShift) {
        public Storage {
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
