package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.impl.FlwImplXplat;

// 26.2: Sodium ships native NeoForge support; Embeddium fork is obsolete.
public enum CompatMod {
    IRIS("iris"),
    SODIUM("sodium");

    public final String id;
    public final boolean isLoaded;

    CompatMod(String modId) {
        id = modId;
        isLoaded = FlwImplXplat.INSTANCE.isModLoaded(modId);
    }
}
