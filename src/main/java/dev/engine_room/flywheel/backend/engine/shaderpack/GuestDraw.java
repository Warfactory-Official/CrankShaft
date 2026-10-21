package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.engine.GroupKey;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import net.minecraft.util.ResourceLocation;

interface GuestDraw {
    GroupKey<?> groupKey();

    MeshPool.PooledMesh mesh();

    Material material();

    GuestDrawMetadata metadata();

    default boolean indirect() {
        return false;
    }

    default int tag(GuestHost host) {
        return metadata().tag(host);
    }

    default int kind(GuestHost host) {
        return metadata().kind(host);
    }

    default int itemTag(GuestHost host) {
        return metadata().itemTag(host);
    }

    default ResourceLocation entityKey() {
        return metadata().entityKey();
    }

    default Material material(boolean crumbling) {
        return metadata().material(crumbling);
    }

    default boolean castsShadow(GuestHost host) {
        return metadata().castsShadow(host);
    }
}
