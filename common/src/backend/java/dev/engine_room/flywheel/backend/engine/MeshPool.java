package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
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

    // The mesh vertex buffer, rebuilt on each pool flush and bound to the RenderPass for every draw path
    // (instancing drawIndexed, plus the indirect/crumbling raw multidraws which inherit it from the pipeline VAO).
    @Nullable
    private GpuBuffer mojangVbo;

    // Per-meshlet model-space bounding spheres (vec4 center+radius), concatenated in meshList order; only built
    // when computeMeshletBounds (the mesh-visual task-cull tiers). Host-agnostic Mojang buffer: the GL tier binds
    // its GlBuffer handle as an SSBO, the VK tier reads it by device address.
    @Nullable
    private GpuBuffer meshletBounds;
    private boolean computeMeshletBounds;

    private boolean dirty;
    private boolean anyToRemove;

    /**
     * Create a new mesh pool.
     */
    public MeshPool() {
        vertexView = InternalVertex.createVertexView();
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
        if (!dirty) {
            return;
        }

        if (anyToRemove) {
            anyToRemove = false;
            processDeletions();
        }

        if (!recentlyAllocated.isEmpty()) {
            // Otherwise, just update the index with the new counts.
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
        // remove deleted meshes
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
                mesh.baseVertex = baseVertex;

                vertexView.ptr(vertexPtr + byteIndex);
                vertexView.vertexCount(mesh.vertexCount());
                mesh.mesh.write(vertexView);

                if (boundsBlock != null) {
                    writeMeshletBounds(mesh, vertexPtr + byteIndex, boundsBlock.ptr() + (long) mesh.meshletBase * 16L,
                            indexScratch.ptr());
                }

                byteIndex += mesh.byteSize();
                baseVertex += mesh.vertexCount();
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

            uploadMeshletBounds(boundsBlock, totalMeshlets);
        } finally {
            if (indexScratch != null) {
                indexScratch.free();
            }
            if (boundsBlock != null) {
                boundsBlock.free();
            }
            vertexBlock.free();
        }
    }

    // One mesh's per-meshlet model-space bounding spheres. Radius is the AABB half-diagonal (conservative -- never
    // under-covers, so a visible meshlet is never wrongly culled). Reads positions from the pooled vertex bytes
    // (InternalVertex pos = f32x3 @0) via the mesh-local index order.
    private void writeMeshletBounds(PooledMesh mesh, long vertsPtr, long outPtr, long idxScratchPtr) {
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
                    int vi = MemoryUtil.memGetInt(idxScratchPtr + (long) (t * 3 + j) * Integer.BYTES);
                    long p = vertsPtr + (long) vi * InternalVertex.STRIDE;
                    float x = MemoryUtil.memGetFloat(p);
                    float y = MemoryUtil.memGetFloat(p + 4L);
                    float z = MemoryUtil.memGetFloat(p + 8L);
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
        }
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
        private int baseVertex = INVALID_BASE_VERTEX;
        // Base index of this mesh's per-meshlet bounding spheres in the pool's meshlet-bounds SSBO; assigned in
        // uploadAll only when computeMeshletBounds. Rides the draw command (IndirectDraw.write) for the task cull.
        private int meshletBase;

        private PooledMesh(Mesh mesh) {
            this.mesh = mesh;
        }

        public int vertexCount() {
            return mesh.vertexCount();
        }

        /**
         * Model-space bounding-sphere radius; the visual depth replay's occluder-worthiness filter reads it.
         */
        public float boundingRadius() {
            return mesh.boundingSphere().w();
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
            return mesh.vertexCount() * InternalVertex.STRIDE;
        }

        public int indexCount() {
            return mesh.indexCount();
        }

        public int baseVertex() {
            return baseVertex;
        }

        public int firstIndex() {
            return MeshPool.this.indexPool.firstIndex(mesh.indexSequence());
        }

        public boolean isInvalid() {
            return mesh.vertexCount() == 0 || baseVertex == INVALID_BASE_VERTEX || isDeleted();
        }

        @Override
        protected void _delete() {
            MeshPool.this.dirty = true;
            MeshPool.this.anyToRemove = true;
        }
    }
}
