package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.internal.DependencyInjection;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The per-loader seam behind {@link PartialModel}, hiding 26.2's two typed standalone-model loaders
 * (NeoForge {@code StandaloneModelKey}, Fabric {@code ExtraModelKey}).
 */
public interface PartialModelController {
    PartialModelController INSTANCE = DependencyInjection.load(PartialModelController.class,
            "dev.engine_room.flywheel.lib.model.baked.PartialModelControllerImpl");

    Object createKey(Identifier modelLocation);

    @Nullable
    BlockStateModel getBaked(Object key);
}
