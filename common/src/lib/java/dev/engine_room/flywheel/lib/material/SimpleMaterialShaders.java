package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.MaterialShaders;
import net.minecraft.resources.Identifier;

public record SimpleMaterialShaders(Identifier vertexSource, Identifier fragmentSource,
                                    boolean preservesPosition) implements MaterialShaders {
    public SimpleMaterialShaders(Identifier vertexSource, Identifier fragmentSource) {
        this(vertexSource, fragmentSource, false);
    }
}
