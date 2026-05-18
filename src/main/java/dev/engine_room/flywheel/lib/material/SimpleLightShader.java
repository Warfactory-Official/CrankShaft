package dev.engine_room.flywheel.lib.material;

import dev.engine_room.flywheel.api.material.LightShader;
import net.minecraft.util.ResourceLocation;

public record SimpleLightShader(@Override ResourceLocation source) implements LightShader {
}
