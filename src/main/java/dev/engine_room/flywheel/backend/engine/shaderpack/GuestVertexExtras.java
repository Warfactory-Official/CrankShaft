package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.vertex.VertexList;
import dev.engine_room.flywheel.backend.engine.MeshVertexExtras;
import dev.engine_room.flywheel.backend.gl.GlNumericType;
import dev.engine_room.flywheel.backend.gl.array.VertexAttribute;
import dev.engine_room.flywheel.lib.model.BlockStateMesh;
import dev.engine_room.flywheel.lib.model.MidUvMesh;
import dev.engine_room.flywheel.lib.model.QuadIndexSequence;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

final class GuestVertexExtras implements MeshVertexExtras {
    private static final int STRIDE = 28;
    private static final List<VertexAttribute> ATTRIBUTES = List.of(
            new VertexAttribute.Float(GlNumericType.FLOAT, 2, false),
            new VertexAttribute.Int(GlNumericType.SHORT, 4),
            new VertexAttribute.Float(GlNumericType.BYTE, 4, true),
            new VertexAttribute.Float(GlNumericType.BYTE, 4, false),
            new VertexAttribute.Float(GlNumericType.BYTE, 4, true));
    private final float[] faceNormal = new float[3];
    private GuestHost blockIds;

    @Override
    public int stride() {
        return STRIDE;
    }

    @Override
    public List<VertexAttribute> attributes() {
        return ATTRIBUTES;
    }

    @Override
    public boolean prepare() {
        var current = GuestHost.current();
        if (current == blockIds) return false;
        blockIds = current;
        return true;
    }

    @Override
    public void write(Mesh mesh, VertexList vertices, long destination) {
        var block = BlockStateMesh.find(mesh);
        var providedUv = mesh instanceof MidUvMesh;
        if (providedUv) ((MidUvMesh) mesh).writeMidUv(destination);
        // Reverse expansion lets the contiguous sprite centres share the final interleaved allocation.
        for (var vertex = mesh.vertexCount() - 1; vertex >= 0; vertex--) {
            var state = block == null ? null : block.blockState(vertex);
            int id = state == null ? -1 : blockIds.blockId(state);
            int renderType = state == null ? -1 : state.getRenderType().ordinal();
            int metadata = state == null ? 0 : state.getBlock().getMetaFromState(state);
            int emission = state == null ? 0 : state.getLightValue();
            var u = providedUv ? MemoryUtil.memGetFloat(destination + (long) vertex * 8) : 0;
            var v = providedUv ? MemoryUtil.memGetFloat(destination + (long) vertex * 8 + 4) : 0;
            var pointer = destination + (long) vertex * STRIDE;
            MemoryUtil.memPutFloat(pointer, u);
            MemoryUtil.memPutFloat(pointer + 4, v);
            MemoryUtil.memPutShort(pointer + 8, (short) id);
            MemoryUtil.memPutShort(pointer + 10, (short) renderType);
            MemoryUtil.memPutShort(pointer + 12, (short) metadata);
            MemoryUtil.memPutShort(pointer + 14, (short) 0);
            MemoryUtil.memPutInt(pointer + 16, 0);
            MemoryUtil.memPutInt(pointer + 20, 0);
            MemoryUtil.memPutInt(pointer + 24, state == null ? 0 : 0x7F000000);
            if (state != null) {
                MemoryUtil.memPutByte(pointer + 20,
                        (byte) Math.round((block.blockCenterX(vertex) - vertices.x(vertex)) * 64));
                MemoryUtil.memPutByte(pointer + 21,
                        (byte) Math.round((block.blockCenterY(vertex) - vertices.y(vertex)) * 64));
                MemoryUtil.memPutByte(pointer + 22,
                        (byte) Math.round((block.blockCenterZ(vertex) - vertices.z(vertex)) * 64));
                MemoryUtil.memPutByte(pointer + 23, (byte) emission);
            }
        }
        if (mesh.indexSequence() != QuadIndexSequence.INSTANCE) return;
        if (mesh.vertexCount() % 4 != 0)
            throw new IllegalArgumentException("Quad mesh has an incomplete quad: " + mesh);
        for (var quad = 0; quad < mesh.vertexCount(); quad += 4) {
            var state = block == null ? null : block.blockState(quad);
            var midU = 0f;
            var midV = 0f;
            for (var vertex = quad; vertex < quad + 4; vertex++) {
                midU += vertices.u(vertex);
                midV += vertices.v(vertex);
            }
            GuestVertexMath.computeFaceNormal(faceNormal, vertices.x(quad), vertices.y(quad), vertices.z(quad),
                    vertices.x(quad + 1), vertices.y(quad + 1), vertices.z(quad + 1), vertices.x(quad + 2),
                    vertices.y(quad + 2), vertices.z(quad + 2),
                    vertices.x(quad + 3), vertices.y(quad + 3), vertices.z(quad + 3));
            for (var vertex = quad; vertex < quad + 4; vertex++) {
                var pointer = destination + (long) vertex * STRIDE;
                if (!providedUv) {
                    MemoryUtil.memPutFloat(pointer, midU * 0.25f);
                    MemoryUtil.memPutFloat(pointer + 4, midV * 0.25f);
                }
                var nx = state == null ? vertices.normalX(vertex) : faceNormal[0];
                var ny = state == null ? vertices.normalY(vertex) : faceNormal[1];
                var nz = state == null ? vertices.normalZ(vertex) : faceNormal[2];
                var tangent = GuestVertexMath.computeSmoothTangent(nx, ny, nz,
                        vertices.x(quad), vertices.y(quad), vertices.z(quad), vertices.u(quad), vertices.v(quad),
                        vertices.x(quad + 1), vertices.y(quad + 1), vertices.z(quad + 1), vertices.u(quad + 1),
                        vertices.v(quad + 1),
                        vertices.x(quad + 2), vertices.y(quad + 2), vertices.z(quad + 2), vertices.u(quad + 2),
                        vertices.v(quad + 2));
                MemoryUtil.memPutInt(pointer + 16, tangent);
                if (state != null)
                    MemoryUtil.memPutInt(pointer + 24, GuestVertexMath.packNormal(nx, ny, nz) | 0x7F000000);
            }
        }
    }
}
