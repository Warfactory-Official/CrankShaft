package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.vertex.VertexList;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import net.minecraft.block.state.IBlockState;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;


/**
 * Baked quads retaining sprite centres and optional block material metadata. Builders and downstream renderers
 * create these on model rebuild and keep their vertex buffers immutable while pooled for rendering.
 */
public final class LitQuadMesh implements QuadMesh, MidUvMesh, BlockStateMesh {
    private final VertexList vertexList;
    private final Vector4f boundingSphere;
    @Nullable
    private final String descriptor;
    private final MemoryBlock midUv;
    @Nullable
    private final IBlockState blockState;
    private final float blockCenterX;
    private final float blockCenterY;
    private final float blockCenterZ;
    @Nullable
    private final IBlockState[] quadStates;
    @Nullable
    private final float[] quadCentres;

    public LitQuadMesh(VertexList vertexList, @Nullable String descriptor, MemoryBlock midUv) {
        this(vertexList, descriptor, midUv, null, 0.5f, 0.5f, 0.5f);
    }

    public LitQuadMesh(VertexList vertexList, @Nullable String descriptor, MemoryBlock midUv,
                       @Nullable IBlockState blockState, float blockCenterX, float blockCenterY, float blockCenterZ) {
        this(vertexList, descriptor, midUv, blockState, blockCenterX, blockCenterY, blockCenterZ, null, null);
    }

    private LitQuadMesh(VertexList vertexList, @Nullable String descriptor, MemoryBlock midUv,
                        @Nullable IBlockState blockState, float blockCenterX, float blockCenterY, float blockCenterZ,
                        @Nullable IBlockState[] states, @Nullable float[] centres) {
        this.vertexList = vertexList;
        this.boundingSphere = ModelUtil.computeBoundingSphere(vertexList);
        this.descriptor = descriptor;
        this.midUv = midUv;
        this.blockState = blockState;
        this.blockCenterX = blockCenterX;
        this.blockCenterY = blockCenterY;
        this.blockCenterZ = blockCenterZ;
        this.quadStates = states;
        this.quadCentres = centres;
    }

    public LitQuadMesh(VertexList vertices, @Nullable String descriptor, MemoryBlock midUv, IBlockState[] states,
                       float[] centres) {
        this(vertices, descriptor, midUv, null, 0.5f, 0.5f, 0.5f, states, centres);
        if (states.length * 4 != vertices.vertexCount() || centres.length != states.length * 3)
            throw new IllegalArgumentException("Baked quad metadata length mismatch");
    }

    @Override
    public @Nullable IBlockState blockState(int vertex) {
        return quadStates == null ? blockState : quadStates[vertex / 4];
    }

    @Override
    public float blockCenterX(int vertex) {
        return quadCentres == null ? blockCenterX : quadCentres[vertex / 4 * 3];
    }

    @Override
    public float blockCenterY(int vertex) {
        return quadCentres == null ? blockCenterY : quadCentres[vertex / 4 * 3 + 1];
    }

    @Override
    public float blockCenterZ(int vertex) {
        return quadCentres == null ? blockCenterZ : quadCentres[vertex / 4 * 3 + 2];
    }

    @Override
    public int vertexCount() {
        return vertexList.vertexCount();
    }

    @Override
    public void write(MutableVertexList dst) {
        vertexList.writeAll(dst);
    }

    @Override
    public Vector4fc boundingSphere() {
        return boundingSphere;
    }

    @Override
    public void writeMidUv(long destPtr) {
        MemoryUtil.memCopy(midUv.ptr(), destPtr, (long) vertexCount() * MID_UV_BYTES_PER_VERTEX);
    }

    @Override
    public String toString() {
        return "LitQuadMesh{vertexCount=" + vertexCount() + ",descriptor={" + descriptor + "}}";
    }
}
