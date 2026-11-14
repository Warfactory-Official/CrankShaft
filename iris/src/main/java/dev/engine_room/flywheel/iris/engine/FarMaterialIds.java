package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.lib.model.PackIdentity;
import dev.engine_room.flywheel.lib.model.PackTaggedModel;
import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Resolves the pack's block identity and DH's independent terrain category once per retained mesh draw.
 */
public final class FarMaterialIds {
    private FarMaterialIds() {
    }

    public static int resolve(Model model, Material _, Mesh mesh, BlockState fallbackState) {
        var ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        BlockState offered = null;
        if (model instanceof PackTaggedModel tagged && ids != null) {
            for (var identity : tagged.identities()) {
                BlockState state = switch (identity) {
                    case PackIdentity.OfBlock(var block) -> block.defaultBlockState();
                    case PackIdentity.OfState(var blockState) -> blockState;
                    default -> null;
                };
                if (state != null) {
                    if (offered == null) offered = state;
                    int id = ids.getOrDefault(state, 0);
                    if (id != 0) return (id & 65535) | DistantHorizonsCompat.material(state) << 16;
                }
            }
        }
        if (mesh instanceof BakedMesh baked && baked.blockState() != null) {
            var state = baked.blockState();
            int id = ids == null ? 0 : ids.getOrDefault(state, 0);
            return (id & 65535) | DistantHorizonsCompat.material(state) << 16;
        }
        var state = offered == null ? fallbackState : offered;
        int id = ids == null ? 0 : ids.getOrDefault(state, 0);
        return (id & 65535) | DistantHorizonsCompat.material(state) << 16;
    }
}
