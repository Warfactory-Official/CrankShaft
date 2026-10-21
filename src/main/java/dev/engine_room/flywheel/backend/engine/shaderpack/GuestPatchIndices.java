package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.backend.engine.IndexPool;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.QuadIndexSequence;
import org.lwjgl.system.MemoryUtil;

import java.util.IdentityHashMap;
import java.util.List;

final class GuestPatchIndices {
    static final IndexSequence QUADS = (ptr, count) -> {
        for (var index = 0; index < count; index++) MemoryUtil.memPutInt(ptr + index * 4L, index);
    };
    private final IndexPool indices = new IndexPool();
    private final IdentityHashMap<IndexSequence, Sequence> sequences = new IdentityHashMap<>();
    private long version;

    static int count(MeshPool.PooledMesh mesh) {
        return Math.multiplyExact(mesh.indexCount() / (mesh.indexSequence() == QuadIndexSequence.INSTANCE ? 6 : 3), 4);
    }

    void prepare(List<MeshPool.PooledMesh> meshes) {
        for (var mesh : meshes) {
            var sequence = sequences.computeIfAbsent(mesh.indexSequence(), Sequence::new);
            var count = count(mesh);
            if (count <= sequence.count) continue;
            sequence.count = count;
            indices.updateCount(sequence, count);
            version++;
        }
        indices.flush();
    }

    int first(MeshPool.PooledMesh mesh) {
        return indices.firstIndex(sequences.get(mesh.indexSequence()));
    }

    int buffer() {
        return indices.bufferHandle();
    }

    long version() {
        return version;
    }

    void delete() {
        indices.delete();
        sequences.clear();
    }

    private static final class Sequence implements IndexSequence {
        private final IndexSequence source;
        private int count;

        Sequence(IndexSequence source) {
            this.source = source;
        }

        @Override
        public void fill(long ptr, int count) {
            if (source == QuadIndexSequence.INSTANCE) {
                QUADS.fill(ptr, count);
                return;
            }
            var triangles = MemoryBlock.malloc(count / 4L * 3 * Integer.BYTES);
            try {
                source.fill(triangles.ptr(), count / 4 * 3);
                // Arbitrary triangle meshes become degenerate quad patches without inventing a fourth corner.
                for (var index = 0; index < count / 4; index++) {
                    var from = triangles.ptr() + index * 12L;
                    var to = ptr + index * 16L;
                    MemoryUtil.memPutInt(to, MemoryUtil.memGetInt(from));
                    MemoryUtil.memPutInt(to + 4, MemoryUtil.memGetInt(from + 4));
                    var last = MemoryUtil.memGetInt(from + 8);
                    MemoryUtil.memPutInt(to + 8, last);
                    MemoryUtil.memPutInt(to + 12, last);
                }
            } finally {
                triangles.free();
            }
        }
    }
}
