package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

/**
 * Immutable source triangles; callers supply the affine position transform actually used by the draw.
 */
public final class MeshFaces {
    private static final int NORMAL_SCALE = 1024;
    private static final Direction.Axis[] AXES = Direction.Axis.values();
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private final float[] positions;
    private final int[] indices;
    private final BitSet degenerate = new BitSet();
    private final Long2ObjectOpenHashMap<int[]> planes = new Long2ObjectOpenHashMap<>();

    public MeshFaces(float[] positions, int[] indices) {
        this(positions, indices, true);
    }

    private MeshFaces(float[] positions, int[] indices, boolean copy) {
        if (positions.length % 3 != 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "Triangle positions/indices: " + positions.length + "/" + indices.length);
        }
        this.positions = copy ? positions.clone() : positions;
        this.indices = copy ? indices.clone() : indices;
        positions = this.positions;
        indices = this.indices;
        var groups = new Long2ObjectOpenHashMap<IntArrayList>();
        for (int face = 0; face < indices.length / 3; face++) {
            int a = vertex(indices[face * 3]), b = vertex(indices[face * 3 + 1]), c = vertex(indices[face * 3 + 2]);
            if (!finite(a) || !finite(b) || !finite(c)) continue;
            double ax = positions[b] - (double) positions[a], ay = positions[b + 1] - (double) positions[a + 1];
            double az = positions[b + 2] - (double) positions[a + 2];
            double bx = positions[c] - (double) positions[a], by = positions[c + 1] - (double) positions[a + 1];
            double bz = positions[c + 2] - (double) positions[a + 2];
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            if (nx == 0 && ny == 0 && nz == 0) {
                degenerate.set(face);
                continue;
            }
            groups.computeIfAbsent(normalKey(nx, ny, nz), _ -> new IntArrayList()).add(face);
        }
        groups.forEach((key, value) -> planes.put(key, value.toIntArray()));
    }

    public static MeshFaces of(Mesh mesh) {
        if (mesh.indexCount() % 3 != 0)
            throw new IllegalArgumentException("Triangle index count: " + mesh.indexCount());
        var positions = new float[Math.multiplyExact(mesh.vertexCount(), 3)];
        var indices = new int[mesh.indexCount()];
        if (mesh.vertexCount() != 0) {
            var memory = MemoryBlock.malloc(Math.multiplyExact(mesh.vertexCount(), FullVertexView.STRIDE));
            try {
                var view = new FullVertexView();
                view.ptr(memory.ptr());
                view.vertexCount(mesh.vertexCount());
                mesh.write(view);
                for (int v = 0; v < mesh.vertexCount(); v++) {
                    positions[v * 3] = view.x(v);
                    positions[v * 3 + 1] = view.y(v);
                    positions[v * 3 + 2] = view.z(v);
                }
            } finally {
                memory.free();
            }
        }
        if (indices.length != 0) {
            var memory = MemoryBlock.malloc((long) indices.length * Integer.BYTES);
            try {
                mesh.indexSequence().fill(memory.ptr(), indices.length);
                for (int i = 0; i < indices.length; i++)
                    indices[i] = MemoryUtil.memGetInt(memory.ptr() + (long) i * Integer.BYTES);
            } finally {
                memory.free();
            }
        }
        return new MeshFaces(positions, indices, false);
    }

    private static long normalKey(double x, double y, double z) {
        double scale = NORMAL_SCALE / Math.sqrt(x * x + y * y + z * z);
        if (x < 0 || x == 0 && (y < 0 || y == 0 && z < 0)) scale = -scale;
        return key((int) Math.round(x * scale), (int) Math.round(y * scale), (int) Math.round(z * scale));
    }

    private static long key(int x, int y, int z) {
        return (x & 0xFFFFL) | (y & 0xFFFFL) << 16 | (z & 0xFFFFL) << 32;
    }

    private static int component(long key, int axis) {
        return (short) (key >>> (axis * 16));
    }

    private int vertex(int index) {
        if (index < 0 || index >= positions.length / 3) throw new IllegalArgumentException("Vertex index: " + index);
        return index * 3;
    }

    private boolean finite(int vertex) {
        return Float.isFinite(positions[vertex]) && Float.isFinite(positions[vertex + 1]) && Float.isFinite(
                positions[vertex + 2]);
    }

    /**
     * Writes source-triangle bits, including degenerate triangles, without changing source vertices or order.
     * Coverage queries run only for posed triangles on exact block planes. Unknown neighbour coverage retains them.
     */
    public void hidden(Matrix4fc pose, int originX, int originY, int originZ, FaceOccluder coverage, BitSet hidden) {
        hidden.clear();
        hidden.or(degenerate);
        var scratch = SCRATCH.get();
        if (pose.determinant3x3() == 0) {
            for (int face = 0; face < triangleCount(); face++) {
                if (hidden.get(face)) continue;
                transform(face, pose, scratch);
                if (scratch.degenerate()) {
                    hidden.set(face);
                    continue;
                }
                for (int axis = 0; axis < 3; axis++) {
                    if (scratch.hidden(axis, originX, originY, originZ, coverage)) {
                        hidden.set(face);
                        break;
                    }
                }
            }
            return;
        }
        for (int axis = 0; axis < 3; axis++) {
            double x = pose.get(0, axis), y = pose.get(1, axis), z = pose.get(2, axis);
            if (x == 0 && y == 0 && z == 0) continue;
            long key = normalKey(x, y, z);
            int kx = component(key, 0), ky = component(key, 1), kz = component(key, 2);
            // Buckets choose candidates only; the posed positions below establish exact coverage.
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int[] faces = planes.get(key(kx + dx, ky + dy, kz + dz));
                        if (faces == null) continue;
                        for (int face : faces) {
                            if (hidden.get(face)) continue;
                            transform(face, pose, scratch);
                            if (scratch.hidden(axis, originX, originY, originZ, coverage)) hidden.set(face);
                        }
                    }
        }
    }

    private void transform(int face, Matrix4fc pose, Scratch scratch) {
        for (int v = 0; v < 3; v++) {
            int at = indices[face * 3 + v] * 3;
            var point = scratch.point.set(positions[at], positions[at + 1], positions[at + 2]);
            pose.transformPosition(point);
            scratch.triangle[v * 3] = point.x;
            scratch.triangle[v * 3 + 1] = point.y;
            scratch.triangle[v * 3 + 2] = point.z;
        }
    }

    public Mesh visible(Mesh source, BitSet hidden) {
        if (hidden.isEmpty()) return source;
        var visible = new IntArrayList(indices.length);
        for (int face = 0; face < indices.length / 3; face++) {
            if (!hidden.get(face)) visible.addElements(visible.size(), indices, face * 3, 3);
        }
        return IndexMesh.takeOwnership(source, visible.toIntArray());
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    private static final class Scratch {
        final Vector3f point = new Vector3f();
        final float[] triangle = new float[9];
        final double[] a = new double[16];
        final double[] b = new double[16];

        boolean degenerate() {
            double ax = triangle[3] - (double) triangle[0], ay = triangle[4] - (double) triangle[1], az = triangle[5] - (double) triangle[2];
            double bx = triangle[6] - (double) triangle[0], by = triangle[7] - (double) triangle[1], bz = triangle[8] - (double) triangle[2];
            return ay * bz - az * by == 0 && az * bx - ax * bz == 0 && ax * by - ay * bx == 0;
        }

        boolean hidden(int axis, int originX, int originY, int originZ, FaceOccluder coverage) {
            float plane = triangle[axis];
            if (!Float.isFinite(plane) || plane != Math.floor(plane) || triangle[axis + 3] != plane
                    || triangle[axis + 6] != plane) return false;
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            double cross = (triangle[3 + u] - (double) triangle[u]) * (triangle[6 + v] - (double) triangle[v])
                    - (triangle[3 + v] - (double) triangle[v]) * (triangle[6 + u] - (double) triangle[u]);
            if (cross == 0) return false;
            var facing = Direction.fromAxisAndDirection(AXES[axis],
                    cross > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            int normal = (int) plane - (cross < 0 ? 1 : 0);
            double minU = Math.min(triangle[u], Math.min(triangle[3 + u], triangle[6 + u]));
            double minV = Math.min(triangle[v], Math.min(triangle[3 + v], triangle[6 + v]));
            double maxU = Math.max(triangle[u], Math.max(triangle[3 + u], triangle[6 + u]));
            double maxV = Math.max(triangle[v], Math.max(triangle[3 + v], triangle[6 + v]));
            for (int j = (int) Math.floor(minV); j < Math.ceil(maxV); j++) {
                for (int i = (int) Math.floor(minU); i < Math.ceil(maxU); i++) {
                    if (!intersects(u, v, i, j)) continue;
                    int x = axis == 0 ? normal : u == 0 ? i : j;
                    int y = axis == 1 ? normal : u == 1 ? i : j;
                    int z = axis == 2 ? normal : u == 2 ? i : j;
                    if (!coverage.occludes(x + originX, y + originY, z + originZ, facing)) return false;
                }
            }
            return true;
        }

        boolean intersects(int u, int v, int x, int y) {
            for (int i = 0; i < 3; i++) {
                a[i * 2] = triangle[i * 3 + u];
                a[i * 2 + 1] = triangle[i * 3 + v];
            }
            int count = 3;
            double[] in = a, out = b;
            for (int p = 0; p < 4 && count != 0; p++) {
                int coordinate = p & 1;
                double edge = (coordinate == 0 ? x : y) + (p >= 2 ? 1 : 0);
                int produced = 0, previous = (count - 1) * 2;
                double before = p < 2 ? in[previous + coordinate] - edge : edge - in[previous + coordinate];
                for (int i = 0; i < count; i++) {
                    int at = i * 2;
                    double distance = p < 2 ? in[at + coordinate] - edge : edge - in[at + coordinate];
                    if ((distance >= 0) != (before >= 0)) {
                        double t = before / (before - distance);
                        out[produced * 2] = in[previous] + t * (in[at] - in[previous]);
                        out[produced * 2 + 1] = in[previous + 1] + t * (in[at + 1] - in[previous + 1]);
                        out[produced * 2 + coordinate] = edge;
                        produced++;
                    }
                    if (distance >= 0) {
                        out[produced * 2] = in[at];
                        out[produced * 2 + 1] = in[at + 1];
                        produced++;
                    }
                    previous = at;
                    before = distance;
                }
                count = produced;
                double[] swap = in;
                in = out;
                out = swap;
            }
            double area = 0;
            for (int i = 0; i < count; i++) {
                int next = (i + 1) % count;
                area += in[i * 2] * in[next * 2 + 1] - in[i * 2 + 1] * in[next * 2];
            }
            return area != 0;
        }
    }
}
