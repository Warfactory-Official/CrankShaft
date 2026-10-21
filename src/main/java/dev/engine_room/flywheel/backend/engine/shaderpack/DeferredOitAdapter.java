package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

interface DeferredOitAdapter {
    ContractPatches.Plan stage(GuestShaderPack pack, Map<String, String> originals,
                               JsonObject recipe) throws IOException;

    String transform(String name, String source, int type);

    void validate(DeferredReplayTargets targets);

    DeferredReplayPlan replayPlan(DeferredReplayTargets targets, List<DeferredReplayPass> passes);

    default boolean capturesLights() {
        return false;
    }

    default String clearShader() {
        return "layer_clear.comp";
    }

    default int[] captureBindings() {
        return capturesLights() ? new int[]{0, 1, 2, 5} : new int[]{0, 1, 2};
    }
}
