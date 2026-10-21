package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.GroupKey;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedInstancer;
import dev.engine_room.flywheel.backend.gl.TextureBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

final class GuestInstancedDraw extends InstancedDraw implements GuestDraw {
    private final GuestDrawMetadata metadata;

    GuestInstancedDraw(InstancedInstancer<?> instancer, MeshPool.PooledMesh pooled, GroupKey<?> group,
                       Material material,
                       int bias, int meshIndex, Mesh mesh, Model model) {
        super(instancer, pooled, group, material, bias, meshIndex);
        metadata = new GuestDrawMetadata(group, material, mesh, model);
    }

    @Override
    public GroupKey<?> groupKey() {
        return groupKey;
    }

    @Override
    public GuestDrawMetadata metadata() {
        return metadata;
    }

    void render(TextureBuffer buffer, GuestPatchIndices patches, boolean one) {
        if (patches == null) {
            if (one) renderOne(buffer);
            else render(buffer);
            return;
        }
        var mesh = mesh();
        if (mesh.isInvalid()) return;
        instancer().bind(buffer);
        GL32.glDrawElementsInstancedBaseVertex(GL40.GL_PATCHES, GuestPatchIndices.count(mesh), GL11.GL_UNSIGNED_INT,
                patches.first(mesh) * 4L, one ? 1 : instancer().instanceCount(), mesh.baseVertex());
    }
}
