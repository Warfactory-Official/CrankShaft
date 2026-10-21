package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.GroupKey;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDraw;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectInstancer;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import org.lwjgl.system.MemoryUtil;

final class GuestIndirectDraw extends IndirectDraw implements GuestDraw {
    private final GroupKey<?> group;
    private final GuestDrawMetadata metadata;

    GuestIndirectDraw(IndirectInstancer<?> instancer, InstancerKey<?> key, Model.ConfiguredMesh configured,
                      MeshPool.PooledMesh pooled, int meshIndex) {
        super(instancer, configured.material(), pooled, key.bias(), meshIndex);
        group = new GroupKey<>(key.type(), key.environment());
        metadata = new GuestDrawMetadata(group, configured.material(), configured.mesh(), key.model());
    }

    @Override
    public GroupKey<?> groupKey() {
        return group;
    }

    @Override
    public GuestDrawMetadata metadata() {
        return metadata;
    }

    @Override
    public boolean indirect() {
        return true;
    }

    @Override
    public void write(long ptr) {
        super.write(ptr);
        MemoryUtil.memPutInt(ptr + 36, InstanceTypeIds.id(group.instanceType()));
    }

    @Override
    public void writeWithOverrides(long ptr, int instanceIndex, Material material) {
        super.writeWithOverrides(ptr, instanceIndex, material);
        MemoryUtil.memPutInt(ptr + 36, InstanceTypeIds.id(group.instanceType()));
    }
}
