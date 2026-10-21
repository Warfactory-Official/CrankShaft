package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.gl.GlPrimitive;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.util.ReferenceCounted;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.MidUvMesh;
import dev.engine_room.flywheel.lib.model.QuadIndexSequence;
import dev.engine_room.flywheel.lib.vertex.VertexView;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MeshPool {
    private static final int EXTRAS_BUFFER_SLOT = 1;
    private static final int EXTRAS_ATTRIB_LOCATION = 6;

    private final VertexView vertexView;
    private final Map<Mesh, PooledMesh> meshes = new HashMap<>();
    private final List<PooledMesh> meshList = new ArrayList<>();
    private final List<PooledMesh> recentlyAllocated = new ArrayList<>();

    private final GlBuffer vbo;
    @Nullable
    private final GlBuffer extrasVbo;
    @Nullable
    private final MeshVertexExtras extras;
    private final IndexPool indexPool;

    private boolean dirty;
    private boolean anyToRemove;

    /**
     * Create a new mesh pool.
     */
    public MeshPool() {
        this(null);
    }

    /**
     * @param extras optional host attributes without changing the public vertex layout
     */
    public MeshPool(@Nullable MeshVertexExtras extras) {
        this.extras = extras;
        vertexView = InternalVertex.createVertexView();
        vbo = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
        extrasVbo = extras == null ? null : new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
        indexPool = new IndexPool();
    }

    /**
     * Allocate a model in the arena.
     *
     * @param mesh The model to allocate.
     * @return A handle to the allocated model.
     */
    public PooledMesh alloc(Mesh mesh) {
        return meshes.computeIfAbsent(mesh, this::_alloc);
    }

    private PooledMesh _alloc(Mesh m) {
        PooledMesh bufferedModel = new PooledMesh(m);
        meshList.add(bufferedModel);
        recentlyAllocated.add(bufferedModel);

        dirty = true;
        return bufferedModel;
    }

    @Nullable
    public MeshPool.PooledMesh get(Mesh mesh) {
        return meshes.get(mesh);
    }

    public void flush() {
        if (extras != null && extras.prepare()) dirty = true;
        if (!dirty) {
            return;
        }

        if (anyToRemove) {
            anyToRemove = false;
            processDeletions();
        }

        if (!recentlyAllocated.isEmpty()) {
            for (PooledMesh mesh : recentlyAllocated) {
                indexPool.updateCount(mesh.mesh.indexSequence(), mesh.indexCount());
            }
            indexPool.flush();
            recentlyAllocated.clear();
        }

        uploadAll();
        dirty = false;
    }

    private void processDeletions() {
        meshList.removeIf(pooledMesh -> {
            boolean deleted = pooledMesh.isDeleted();
            if (deleted) {
                meshes.remove(pooledMesh.mesh);
            }
            return deleted;
        });
    }

    private void uploadAll() {
        long neededSize = 0;
        for (PooledMesh mesh : meshList) {
            neededSize += mesh.byteSize();
        }

        final var vertexBlock = MemoryBlock.malloc(neededSize);
        final long vertexPtr = vertexBlock.ptr();

        MemoryBlock extrasBlock = null;
        long extrasPtr = 0;
        long extrasNeeded = 0;
        try {
            if (extras != null) {
                for (PooledMesh mesh : meshList) {
                    extrasNeeded += (long) mesh.vertexCount() * extras.stride();
                }
                extrasBlock = MemoryBlock.calloc(extrasNeeded, 1);
                extrasPtr = extrasBlock.ptr();
            }
            int byteIndex = 0;
            int baseVertex = 0;
            for (PooledMesh mesh : meshList) {
                mesh.baseVertex = baseVertex;

                vertexView.ptr(vertexPtr + byteIndex);
                vertexView.vertexCount(mesh.vertexCount());
                mesh.mesh.write(vertexView);

                if (extras != null)
                    extras.write(mesh.mesh, vertexView, extrasPtr + (long) baseVertex * extras.stride());

                byteIndex += mesh.byteSize();
                baseVertex += mesh.vertexCount();
            }

            vbo.upload(vertexBlock);

            if (extrasVbo != null) extrasVbo.upload(extrasPtr, extrasNeeded);
        } finally {
            vertexBlock.free();
            if (extrasBlock != null) extrasBlock.free();
        }
    }

    public void bind(GlVertexArray vertexArray) {
        indexPool.bind(vertexArray);
        vertexArray.bindVertexBuffer(0, vbo.handle(), 0, InternalVertex.STRIDE);
        vertexArray.bindAttributes(0, 0, InternalVertex.ATTRIBUTES);
        if (extrasVbo != null) {
            vertexArray.bindVertexBuffer(EXTRAS_BUFFER_SLOT, extrasVbo.handle(), 0, extras.stride());
            vertexArray.bindAttributes(EXTRAS_BUFFER_SLOT, EXTRAS_ATTRIB_LOCATION, extras.attributes());
        }
    }

    public void delete() {
        vbo.delete();
        if (extrasVbo != null) extrasVbo.delete();
        indexPool.delete();
        meshes.clear();
        meshList.clear();
    }

    public List<PooledMesh> pooledMeshes() {
        return meshList;
    }

    public int vertexBuffer() {
        return vbo.handle();
    }

    public int indexBuffer() {
        return indexPool.bufferHandle();
    }

    public class PooledMesh extends ReferenceCounted {
        public static final int INVALID_BASE_VERTEX = -1;

        private final Mesh mesh;
        private int baseVertex = INVALID_BASE_VERTEX;

        private PooledMesh(Mesh mesh) {
            this.mesh = mesh;
        }

        public int vertexCount() {
            return mesh.vertexCount();
        }

        public boolean hasMidUv() {
            return mesh instanceof MidUvMesh || mesh.indexSequence() == QuadIndexSequence.INSTANCE;
        }

        public int byteSize() {
            return mesh.vertexCount() * InternalVertex.STRIDE;
        }

        public int indexCount() {
            return mesh.indexCount();
        }

        public IndexSequence indexSequence() {
            return mesh.indexSequence();
        }

        public int baseVertex() {
            return baseVertex;
        }

        public int firstIndex() {
            return MeshPool.this.indexPool.firstIndex(mesh.indexSequence());
        }

        public long firstIndexByteOffset() {
            return (long) firstIndex() * Integer.BYTES;
        }

        public boolean isInvalid() {
            return mesh.vertexCount() == 0 || baseVertex == INVALID_BASE_VERTEX || isDeleted();
        }

        public void draw(int instanceCount) {
            if (instanceCount > 1) {
                GL32.glDrawElementsInstancedBaseVertex(GlPrimitive.TRIANGLES.glEnum, mesh.indexCount(),
                        GL11.GL_UNSIGNED_INT, firstIndexByteOffset(), instanceCount, baseVertex);
            } else {
                GL32.glDrawElementsBaseVertex(GlPrimitive.TRIANGLES.glEnum, mesh.indexCount(), GL11.GL_UNSIGNED_INT,
                        firstIndexByteOffset(), baseVertex);
            }
        }

        @Override
        protected void _delete() {
            MeshPool.this.dirty = true;
            MeshPool.this.anyToRemove = true;
        }
    }
}
