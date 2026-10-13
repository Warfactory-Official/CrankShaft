package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Shader providers whose generated stages already contain their complete include graph. */
@FunctionalInterface
public interface AssembledShaderSource extends ShaderSource {
    @Override
    default @Nullable CachedIncludeSource getInclude(Identifier id) {
        return null;
    }

    @Override
    default void close() {
    }
}
