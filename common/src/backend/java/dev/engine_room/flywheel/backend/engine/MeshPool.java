package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.util.ReferenceCounted;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.VertexView;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MeshPool {
    // gl_mesh_shader task-cull: triangles per meshlet, matching the shader's _FLW_TRIS_PER_MESHLET. Per-meshlet
    // bounding spheres are computed over this granularity for the unwelded (large-mesh) task-stage frustum cull.
    static final int MESHLET_TRIS = 64;

    private final VertexView vertexView;
    private final Map<Mesh, PooledMesh> meshes = new HashMap<>();
    private final List<PooledMesh> meshList = new ArrayList<>();
    private final List<PooledMesh> recentlyAllocated = new ArrayList<>();

    private final IndexPool indexPool;

    // The mesh vertex buffer, rebuilt when vertex sources change and bound to every draw path
    // (instancing drawIndexed, plus the indirect/crumbling raw multidraws which inherit it from the pipeline VAO).
    @Nullable
    private GpuBuffer mojangVbo;

    // Per-meshlet model-space bounding spheres (vec4 center+radius), concatenated in meshList order; only built
    // when computeMeshletBounds (the mesh-visual task-cull tiers). Host-agnostic Mojang buffer: the GL tier binds
    // its GlBuffer handle as an SSBO, the VK tier reads it by device address.
    @Nullable
    private GpuBuffer meshletBounds;
    private boolean computeMeshletBounds;

    @Nullable
    private MeshVertexExtras extras;
    @Nullable
    private GpuBuffer extrasVbo;

    private boolean dirty;
    private boolean verticesDirty;
    private boolean anyToRemove;

    /**
     * Create a new mesh pool.
     */
    public MeshPool() {
        vertexView = InternalVertex.createVertexView();
        indexPool = new IndexPool();
    }

    private static Mesh canonicalVertexSource(Mesh mesh) {
        var source = mesh.vertexSource();
        if (source.vertexCount() != mesh.vertexCount())
            throw new IllegalArgumentException("Vertex source count differs");
        return source == mesh ? mesh : canonicalVertexSource(source);
    }

    /**
     * Allocate a model in the arena.
     *
     * @param mesh The model to allocate.
     * @return A handle to the allocated model.
     */
    public PooledMesh alloc(Mesh mesh) {
        var existing = meshes.get(mesh);
        if (existing != null && !existing.isDeleted()) return existing;
        var source = canonicalVertexSource(mesh);
        var vertices = source == mesh ? null : alloc(source);
        if (vertices != null) vertices.acquire();
        var allocated = new PooledMesh(mesh, vertices);
        if (vertices == null) verticesDirty = true;
        meshes.put(mesh, allocated);
        meshList.add(allocated);
        recentlyAllocated.add(allocated);
        dirty = true;
        return allocated;
    }

    @Nullable
    public MeshPool.PooledMesh get(Mesh mesh) {
        return meshes.get(mesh);
    }

    public void flush() {
        if (!dirty) {
            return;
        }

        if (anyToRemove) {
            anyToRemove = false;
            processDeletions();
            recentlyAllocated.removeIf(PooledMesh::isDeleted);
            indexPool.reset();
            for (var mesh : meshList) indexPool.updateCount(mesh.mesh.indexSequence(), mesh.indexCount());
        }

        if (!recentlyAllocated.isEmpty()) {
            // Otherwise, just update the index with the new counts.
            for (PooledMesh mesh : recentlyAllocated) {
                indexPool.updateCount(mesh.mesh.indexSequence(), mesh.indexCount());
            }
            recentlyAllocated.clear();
        }
        indexPool.flush();

        if (verticesDirty) uploadAll();
        else uploadBounds();
        verticesDirty = false;
        dirty = false;
    }

    private void uploadBounds() {
        int total = 0, maxIndices = 0;
        for (var mesh : meshList) {
            if (mesh.vertices != null) mesh.baseVertex = mesh.vertices.baseVertex;
            mesh.meshletBase = total;
            total += mesh.meshletCount();
            maxIndices = Math.max(maxIndices, mesh.indexCount());
        }
        if (!computeMeshletBounds || total == 0) return;
        var bounds = MemoryBlock.malloc((long) total * 16);
        var indices = MemoryBlock.malloc((long) maxIndices * Integer.BYTES);
        try {
            for (var mesh : meshList) {
                writeMeshletBounds(mesh, mesh.vertices == null ? mesh.positions : mesh.vertices.positions,
                        bounds.ptr() + (long) mesh.meshletBase * 16, indices.ptr());
            }
            uploadMeshletBounds(bounds, total);
        } finally {
            indices.free();
            bounds.free();
        }
    }

    private void processDeletions() {
        // remove deleted meshes
        meshList.removeIf(pooledMesh -> {
            boolean deleted = pooledMesh.isDeleted();
            if (deleted) {
                meshes.remove(pooledMesh.mesh, pooledMesh);
                if (pooledMesh.vertices == null) verticesDirty = true;
            }
            return deleted;
        });
    }

    private void uploadAll() {
        long neededSize = 0;
        int totalMeshlets = 0;
        int maxIndexCount = 0;
        for (PooledMesh mesh : meshList) {
            neededSize += mesh.byteSize();
            if (computeMeshletBounds) {
                mesh.meshletBase = totalMeshlets;
                totalMeshlets += mesh.meshletCount();
                maxIndexCount = Math.max(maxIndexCount, mesh.indexCount());
            }
        }

        final var vertexBlock = MemoryBlock.malloc(neededSize);
        final long vertexPtr = vertexBlock.ptr();
        final int extrasStride = extras != null ? extras.format()
                                                        .getVertexSize() : 0;
        final MemoryBlock extrasBlock = extrasStride > 0 && neededSize > 0 ? MemoryBlock.malloc(
                neededSize / InternalVertex.STRIDE * extrasStride) : null;

        // Model-space per-meshlet bounding spheres for the task-cull tier: filled from the just-written vertex
        // positions + the mesh's index order (same order the mesh shader walks), so bounds map 1:1 to meshlets.
        final MemoryBlock boundsBlock = (computeMeshletBounds && totalMeshlets > 0) ? MemoryBlock.malloc(
                (long) totalMeshlets * 16L) : null;
        final MemoryBlock indexScratch = (boundsBlock != null && maxIndexCount > 0) ? MemoryBlock.malloc(
                (long) maxIndexCount * Integer.BYTES) : null;

        // Port: mesh.write / createBuffer can throw; free the native blocks on the way out either way.
        try {
            int byteIndex = 0;
            int baseVertex = 0;
            for (PooledMesh mesh : meshList) {
                if (mesh.vertices == null) {
                    mesh.baseVertex = baseVertex;
                    vertexView.ptr(vertexPtr + byteIndex);
                    vertexView.vertexCount(mesh.vertexCount());
                    mesh.mesh.write(vertexView);
                    if (computeMeshletBounds) {
                        var vertexCount = mesh.vertexCount();
                        var positions = mesh.positions;
                        if (positions == null || positions.length != vertexCount * 3) {
                            mesh.positions = positions = new float[vertexCount * 3];
                        }
                        for (var v = 0; v < vertexCount; v++) {
                            positions[v * 3] = vertexView.x(v);
                            positions[v * 3 + 1] = vertexView.y(v);
                            positions[v * 3 + 2] = vertexView.z(v);
                        }
                    } else {
                        mesh.positions = null;
                    }
                    if (extrasBlock != null) {
                        extras.write(mesh.mesh, vertexView, extrasBlock.ptr() + (long) baseVertex * extrasStride);
                    }
                    byteIndex += mesh.byteSize();
                    baseVertex += mesh.vertexCount();
                } else {
                    mesh.baseVertex = mesh.vertices.baseVertex;
                }

                if (boundsBlock != null) {
                    writeMeshletBounds(mesh, mesh.vertices == null ? mesh.positions : mesh.vertices.positions,
                            boundsBlock.ptr() + (long) mesh.meshletBase * 16L,
                            indexScratch.ptr());
                }
            }

            if (mojangVbo != null) {
                // NOT close(): raw-VK consumers (raw vertex/index binds, the MV tiers' BDA reads) are invisible
                // to Mojang's usage tracking, and a rebuild fires exactly when a visual type is added/removed --
                // an immediate close is a use-after-free under the in-flight frames (device loss).
                BufferRetirement.retire(mojangVbo);
                mojangVbo = null;
            }
            if (neededSize > 0) {
                ByteBuffer vertexData = MemoryUtil.memByteBuffer(vertexPtr, (int) neededSize);
                mojangVbo = RenderSystem.getDevice()
                                        .createBuffer(() -> "flywheel mesh pool",
                                                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertexData);
            }
            if (extrasVbo != null) {
                BufferRetirement.retire(extrasVbo);
                extrasVbo = null;
            }
            if (extrasBlock != null) {
                extrasVbo = RenderSystem.getDevice()
                                        .createBuffer(() -> "flywheel mesh pool extras",
                                                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                                                MemoryUtil.memByteBuffer(extrasBlock.ptr(), (int) extrasBlock.size()));
            }

            uploadMeshletBounds(boundsBlock, totalMeshlets);
        } finally {
            if (indexScratch != null) {
                indexScratch.free();
            }
            if (boundsBlock != null) {
                boundsBlock.free();
            }
            if (extrasBlock != null) {
                extrasBlock.free();
            }
            vertexBlock.free();
        }
    }

    // One mesh's per-meshlet model-space bounding spheres. Radius is the AABB half-diagonal (conservative -- never
    // under-covers, so a visible meshlet is never wrongly culled). Reads positions from the pooled vertex bytes
    // (InternalVertex pos = f32x3 @0) via the mesh-local index order.
    private void writeMeshletBounds(PooledMesh mesh, float[] positions, long outPtr, long idxScratchPtr) {
        int indexCount = mesh.indexCount();
        int triCount = indexCount / 3;
        if (triCount == 0) {
            return;
        }
        mesh.mesh.indexSequence()
                 .fill(idxScratchPtr, indexCount);

        int meshlets = mesh.meshletCount();
        for (int m = 0; m < meshlets; m++) {
            int triStart = m * MESHLET_TRIS;
            int triEnd = Math.min(triStart + MESHLET_TRIS, triCount);

            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int t = triStart; t < triEnd; t++) {
                for (int j = 0; j < 3; j++) {
                    int vi = MemoryUtil.memGetInt(idxScratchPtr + (t * 3L + j) * Integer.BYTES);
                    float x = positions[vi * 3];
                    float y = positions[vi * 3 + 1];
                    float z = positions[vi * 3 + 2];
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }

            float cx = (minX + maxX) * 0.5f;
            float cy = (minY + maxY) * 0.5f;
            float cz = (minZ + maxZ) * 0.5f;
            float rx = maxX - cx, ry = maxY - cy, rz = maxZ - cz;
            float radius = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);

            long o = outPtr + (long) m * 16L;
            MemoryUtil.memPutFloat(o, cx);
            MemoryUtil.memPutFloat(o + 4L, cy);
            MemoryUtil.memPutFloat(o + 8L, cz);
            MemoryUtil.memPutFloat(o + 12L, radius);
        }
    }

    private void uploadMeshletBounds(@Nullable MemoryBlock boundsBlock, int totalMeshlets) {
        if (meshletBounds != null) {
            BufferRetirement.retire(meshletBounds); // BDA-read by the MV task culls -- see the vbo comment above.
            meshletBounds = null;
        }
        if (boundsBlock == null || totalMeshlets == 0) {
            return;
        }
        ByteBuffer data = MemoryUtil.memByteBuffer(boundsBlock.ptr(), totalMeshlets * 16);
        meshletBounds = RenderSystem.getDevice()
                                    .createBuffer(() -> "flywheel meshlet bounds",
                                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data);
    }

    /**
     * Enable/disable per-meshlet bounding-sphere generation (the mesh-visual task-cull tiers). Re-flushes on change.
     */
    public void setComputeMeshletBounds(boolean enabled) {
        if (enabled != computeMeshletBounds) {
            computeMeshletBounds = enabled;
            dirty = true;
            verticesDirty = true;
        }
    }

    /**
     * Install or remove the binding-1 stream; {@code null} drops it. Re-flushes when changed.
     */
    public void extras(@Nullable MeshVertexExtras extras) {
        if (extras != this.extras) {
            this.extras = extras;
            dirty = true;
            verticesDirty = true;
        }
    }

    /**
     * Rewrite the binding-1 stream at the next flush (its source data changed).
     */
    public void invalidateExtras() {
        if (extras != null) {
            dirty = true;
            verticesDirty = true;
        }
    }

    /**
     * Sets vertex binding 1 when extras are installed; every pipeline drawn in {@code pass} must then declare it.
     */
    public void bindExtras(RenderPass pass) {
        if (extrasVbo != null) {
            pass.setVertexBuffer(1, extrasVbo.slice());
        }
    }

    /**
     * Pool-owned binding-1 stream; re-query after {@link #flush()} because growth replaces the buffer.
     */
    @Nullable
    public GpuBuffer extrasBuffer() {
        return extrasVbo;
    }

    /**
     * The per-meshlet bounding-sphere buffer, or null when not built.
     */
    @Nullable
    public GpuBuffer meshletBounds() {
        return meshletBounds;
    }

    /**
     * The vertex buffer as a Mojang GpuBuffer, bound to the RenderPass for every draw path.
     */
    @Nullable
    public GpuBuffer vertexBuffer() {
        return mojangVbo;
    }

    /**
     * The index buffer as a Mojang GpuBuffer, for the RenderPass instancing draw path.
     */
    @Nullable
    public GpuBuffer indexBuffer() {
        return indexPool.indexBuffer();
    }

    public void delete() {
        if (mojangVbo != null) {
            BufferRetirement.retire(mojangVbo);
            mojangVbo = null;
        }
        if (meshletBounds != null) {
            BufferRetirement.retire(meshletBounds);
            meshletBounds = null;
        }
        if (extrasVbo != null) {
            BufferRetirement.retire(extrasVbo);
            extrasVbo = null;
        }
        indexPool.delete();
        meshes.clear();
        meshList.clear();
    }

    public List<PooledMesh> pooledMeshes() {
        return meshList;
    }

    public class PooledMesh extends ReferenceCounted {
        public static final int INVALID_BASE_VERTEX = -1;

        private final Mesh mesh;
        private final @Nullable PooledMesh vertices;
        private float @Nullable [] positions;
        private int baseVertex = INVALID_BASE_VERTEX;
        // Base index of this mesh's per-meshlet bounding spheres in the pool's meshlet-bounds SSBO; assigned in
        // uploadAll only when computeMeshletBounds. Rides the draw command (IndirectDraw.write) for the task cull.
        private int meshletBase;

        private PooledMesh(Mesh mesh, @Nullable PooledMesh vertices) {
            this.mesh = mesh;
            this.vertices = vertices;
        }

        public int vertexCount() {
            return mesh.vertexCount();
        }

        /**
         * Number of 64-triangle meshlets this mesh spans.
         */
        public int meshletCount() {
            int tris = indexCount() / 3;
            return (tris + MESHLET_TRIS - 1) / MESHLET_TRIS;
        }

        public int meshletBase() {
            return meshletBase;
        }

        public int byteSize() {
            return vertices == null ? mesh.vertexCount() * InternalVertex.STRIDE : 0;
        }

        public int indexCount() {
            return mesh.indexCount();
        }

        public int baseVertex() {
            return baseVertex;
        }

        public int firstIndex() {
            return indexPool.firstIndex(mesh.indexSequence());
        }

        public boolean isInvalid() {
            return mesh.vertexCount() == 0 || mesh.indexCount() == 0 || baseVertex == INVALID_BASE_VERTEX || isDeleted();
        }

        @Override
        protected void _delete() {
            if (vertices != null) vertices.release();
            dirty = true;
            anyToRemove = true;
        }
    }
}
