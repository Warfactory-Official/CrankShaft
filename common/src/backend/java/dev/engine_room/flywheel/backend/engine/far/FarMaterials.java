package dev.engine_room.flywheel.backend.engine.far;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Material identifiers supplied by the active surface consumer. Register before captures render; resolution runs
 * on the render thread when retained draw commands are built. The result packs the block identifier into bits
 * 0-15 and the LoD material category into bits 16-19. A resource reload rebuilds those commands.
 */
public final class FarMaterials {
    private static Resolver resolver = (_, _, _, _) -> 0;

    private FarMaterials() {
    }

    public static void register(Resolver resolver) {
        FarMaterials.resolver = resolver;
    }

    static int resolve(Model model, Material material, Mesh mesh, BlockState fallbackState) {
        return resolver.resolve(model, material, mesh, fallbackState);
    }

    @FunctionalInterface
    public interface Resolver {
        int resolve(Model model, Material material, Mesh mesh, BlockState fallbackState);
    }
}
