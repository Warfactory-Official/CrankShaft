package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.impl.FlwImplXplat;

// 26.2: Sodium ships native NeoForge support; Embeddium fork is obsolete.
public enum CompatMod {
    ENTITY_CULLING("entityculling"),
    ENTITY_MODEL_FEATURES("entity_model_features"),
    ENTITY_TEXTURE_FEATURES("entity_texture_features"),
    IRIS("iris"),
    LAMBDYNLIGHTS("lambdynlights"),
    POLYTONE("polytone"),
    SODIUM("sodium"),
    VITRAIL("vitrail"),
    VOXY("voxy");

    public final String id;
    public final boolean isLoaded;

    CompatMod(String modId) {
        id = modId;
        isLoaded = FlwImplXplat.INSTANCE.isModLoaded(modId);
    }
}
