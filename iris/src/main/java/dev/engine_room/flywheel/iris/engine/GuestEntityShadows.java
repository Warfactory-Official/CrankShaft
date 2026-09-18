package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.material.Material;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.resources.Identifier;

/**
 * Iris drops vanilla entity blob shadows for packs that disable them; the instanced {@code ShadowComponent} decals
 * follow. Its texture identifies them in type-erased indirect batches too (guests split batches by texture).
 */
final class GuestEntityShadows {
    private static final Identifier BLOB_SHADOW_TEXTURE = Identifier.withDefaultNamespace("textures/misc/shadow.png");

    private GuestEntityShadows() {
    }

    static boolean suppressed() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager()
                                              .getPipelineNullable();
        return pipeline != null && pipeline.shouldDisableVanillaEntityShadows();
    }

    static boolean isBlobShadow(Material material) {
        return material.texture()
                       .equals(BLOB_SHADOW_TEXTURE);
    }
}
