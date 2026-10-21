package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.gl.GlCompat;

import java.io.IOException;
import java.util.List;

enum DeferredOitProfile {
    SUNDIAL, ITERATION, SEUS, SEUS_HRR3;

    private static final List<JsonObject> RECIPES = List.of("sundial", "iteration", "seus_hrr3", "seus_hrr21",
                                                                "seus_gfme")
                                                        .stream().map(name -> JsonParser.parseString(
                    DeferredReplayShaders.resource("recipes/" + name + ".json")).getAsJsonObject()).toList();

    static Checked select(GuestShaderPack pack, boolean authoredOit) throws IOException {
        if (authoredOit || !Boolean.parseBoolean(System.getProperty("flw.shaderpack.oit.deferred", "true"))) return null;
        var recipe = GuestPackPatches.select(pack, RECIPES);
        if (recipe == null) return null;
        if (!GlCompat.SUPPORTS_DSA || !GlCompat.CAPABILITIES.OpenGL45) {
            FlwBackend.LOGGER.info("Deferred material replay requires OpenGL 4.5; preserving native rendering");
            return null;
        }
        var deferred = recipe.getAsJsonObject("deferred");
        if (!GuestPackPatches.accepts(pack, deferred.getAsJsonObject("sources"))) {
            FlwBackend.LOGGER.warn("Unvalidated deferred material sources for {}; preserving native rendering",
                    recipe.get("name").getAsString());
            return null;
        }
        return new Checked(valueOf(deferred.get("profile").getAsString()), recipe);
    }

    DeferredOitAdapter adapter() {
        return switch (this) {
            case SUNDIAL -> SundialDeferredAdapter.INSTANCE;
            case ITERATION -> IterationDeferredAdapter.INSTANCE;
            case SEUS -> SeusDeferredAdapter.STANDARD;
            case SEUS_HRR3 -> SeusDeferredAdapter.MOTION;
        };
    }

    record Checked(DeferredOitProfile profile, JsonObject recipe) {
    }
}
