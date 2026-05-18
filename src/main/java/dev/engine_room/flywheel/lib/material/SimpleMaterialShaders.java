package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.MaterialShaders;
import net.minecraft.util.ResourceLocation;

public record SimpleMaterialShaders(ResourceLocation vertexSource, ResourceLocation fragmentSource) implements MaterialShaders {
}
