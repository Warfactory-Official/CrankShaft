package dev.engine_room.flywheel.backend.lighting;

import dev.engine_room.flywheel.api.lighting.GeometryOcclusion;
import dev.engine_room.flywheel.api.lighting.OcclusionMesh;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.Vec3i;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared triangle scene with immutable mesh trees and refittable instance bounds. Both loaders consume
 * the same packed words. Owners may enqueue meshes and update their own poses from visual workers;
 * preparation and deletion run on the render thread after those owners' update barriers have joined.
 * Each GPU consumer tracks layout and pose revisions independently.
 */
public final class GeometryAoStorage implements GeometryOcclusion {
    public static final int UNCHANGED = 0, POSES_CHANGED = 1, LAYOUT_CHANGED = 2;
    // Scene-relative word offsets are shared with geometry_ao.glsl; poses follow immutable mesh data.
    static final int HEADER_WORDS = 8;
    static final int INSTANCE_WORDS = 40;
    private final ConcurrentLinkedQueue<Handle> pending = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Handle> changed = new AtomicReference<>();
    private final ArrayList<Handle> instances = new ArrayList<>();
    private final IntArrayList changedIndices = new IntArrayList();
    private final IntArrayList changedPoses = new IntArrayList();
    private final IntArrayList touchedNodes = new IntArrayList();
    private final BitSet markedNodes = new BitSet();
    private final BitSet changedWords = new BitSet();
    private final IdentityHashMap<OcclusionMesh, GeometryAoMesh> meshLookup = new IdentityHashMap<>();
    private final ArrayList<GeometryAoMesh> meshes = new ArrayList<>();
    private volatile boolean dirty;
    private boolean closed;
    private long layoutRevision, poseRevision;
    private boolean ambientOcclusion = true, headerChanged;
    private int originX, originY, originZ;
    private int[] staticData = new int[0];
    private int[] dynamicData = new int[0];
    private int[] sceneNodes = new int[0];
    private OcclusionBvh.Topology topology;
    private float[] bounds = new float[0];
    private final Vector3f point = new Vector3f();
    private final float[] matrix = new float[16];

    @Override
    public Occluder add(OcclusionMesh mesh, Vec3i anchor, Matrix4fc pose) {
        if (closed) throw new IllegalStateException();
        Handle handle = new Handle(mesh);
        handle.transform(anchor, pose);
        pending.add(handle);
        dirty = true;
        return handle;
    }

    public int prepare(Vec3i origin) {
        if (!dirty && origin.getX() == originX && origin.getY() == originY && origin.getZ() == originZ)
            return UNCHANGED;
        boolean originChanged = origin.getX() != originX || origin.getY() != originY || origin.getZ() != originZ;
        originX = origin.getX();
        originY = origin.getY();
        originZ = origin.getZ();
        dirty = false;
        boolean structural = false;
        for (Handle handle; (handle = pending.poll()) != null; ) {
            if (handle.deleted) continue;
            GeometryAoMesh mesh = meshLookup.get(handle.source);
            if (mesh == null) {
                mesh = new GeometryAoMesh(handle.source);
                meshLookup.put(handle.source, mesh);
                meshes.add(mesh);
            }
            mesh.references++;
            handle.mesh = mesh;
            instances.add(handle);
            structural = true;
        }
        changedIndices.clear();
        for (Handle handle = changed.getAndSet(null); handle != null; ) {
            Handle next = handle.nextChanged;
            handle.nextChanged = null;
            handle.enqueued = false;
            if (handle.deleted && handle.index >= 0) structural = true;
            else if (!handle.deleted && handle.index >= 0) changedIndices.add(handle.index);
            handle = next;
        }
        if (structural) {
            for (int i = instances.size() - 1; i >= 0; i--) {
                Handle handle = instances.get(i);
                if (!handle.deleted) continue;
                instances.remove(i);
                handle.index = -1;
                if (--handle.mesh.references == 0) {
                    meshLookup.remove(handle.source);
                    meshes.remove(handle.mesh);
                }
            }
        }
        if (instances.isEmpty()) {
            boolean changed = staticData.length != 0;
            staticData = new int[0];
            dynamicData = new int[0];
            sceneNodes = new int[0];
            bounds = new float[0];
            topology = null;
            if (changed) {
                layoutRevision++;
                poseRevision++;
            }
            return changed ? LAYOUT_CHANGED : UNCHANGED;
        }
        if (structural) {
            var words = new IntArrayList();
            words.size(HEADER_WORDS);
            var masks = new IdentityHashMap<OcclusionMesh.Cutout, Integer>();
            for (GeometryAoMesh mesh : meshes) {
                if (mesh.cutout == null) continue;
                Integer offset = masks.get(mesh.cutout);
                if (offset == null) {
                    offset = words.size();
                    masks.put(mesh.cutout, offset);
                    var mask = mesh.cutout;
                    words.add(mask.width());
                    words.add(mask.height() | (mask.nearest() ? Integer.MIN_VALUE : 0));
                    words.add(Float.floatToRawIntBits(mask.threshold()));
                    int texels = mask.width() * mask.height();
                    for (int texel = 0; texel < texels; texel += 4) {
                        int packed = 0;
                        for (int i = 0; i < 4 && texel + i < texels; i++) packed |= mask.alpha(texel + i) << (i * 8);
                        words.add(packed);
                    }
                }
                mesh.cutoutOffset = offset;
            }
            for (GeometryAoMesh mesh : meshes) mesh.append(words);
            staticData = words.toIntArray();
        }
        int nodeWords = (instances.size() * 2 - 1) * OcclusionBvh.NODE_WORDS;
        int dynamicWords = nodeWords + instances.size() * INSTANCE_WORDS;
        if (dynamicData.length != dynamicWords) dynamicData = new int[dynamicWords];
        if (bounds.length != instances.size() * 6) bounds = new float[instances.size() * 6];
        boolean posesChanged = structural || originChanged;
        changedPoses.clear();
        changedWords.clear();
        int count = structural || originChanged ? instances.size() : changedIndices.size();
        for (int j = 0; j < count; j++) {
            int i = structural || originChanged ? j : changedIndices.getInt(j);
            Handle handle = instances.get(i);
            if (structural) handle.index = i;
            int at = nodeWords + i * INSTANCE_WORDS;
            if (structural || originChanged || handle.poseChanged) {
                handle.writePose(bounds, i * 6, dynamicData, at, point, matrix);
                handle.poseChanged = false;
                posesChanged = true;
                if (!structural && !originChanged) changedPoses.add(i);
            }
            if (structural || handle.parametersChanged) {
                handle.writeParameters(dynamicData, at);
                handle.parametersChanged = false;
            }
            if (!structural && !originChanged) changedWords.set(at, at + INSTANCE_WORDS);
        }
        if (structural) {
            topology = OcclusionBvh.buildTopology(bounds);
            sceneNodes = topology.nodes();
        } else if (originChanged) {
            OcclusionBvh.refit(sceneNodes, bounds);
        } else if (!changedPoses.isEmpty()) {
            OcclusionBvh.refitSparse(topology, bounds, changedPoses, touchedNodes, markedNodes);
        }
        staticData[0] = staticData.length;
        staticData[1] = sceneNodes.length / OcclusionBvh.NODE_WORDS;
        staticData[2] = staticData.length + sceneNodes.length;
        staticData[3] = instances.size();
        staticData[4] = originX;
        staticData[5] = originY;
        staticData[6] = originZ;
        staticData[7] = ambientOcclusion ? 1 : 0;
        if (structural || originChanged) {
            System.arraycopy(sceneNodes, 0, dynamicData, 0, sceneNodes.length);
        } else if (posesChanged) {
            for (int i = 0; i < touchedNodes.size(); i++) {
                int at = touchedNodes.getInt(i) * OcclusionBvh.NODE_WORDS;
                System.arraycopy(sceneNodes, at, dynamicData, at, OcclusionBvh.NODE_WORDS);
                changedWords.set(at, at + OcclusionBvh.NODE_WORDS);
            }
        }
        boolean layoutChanged = structural || originChanged || headerChanged;
        if (layoutChanged) layoutRevision++;
        headerChanged = false;
        poseRevision++;
        return layoutChanged ? LAYOUT_CHANGED : POSES_CHANGED;
    }

    public void ambientOcclusion(boolean enabled) {
        if (ambientOcclusion == enabled) return;
        ambientOcclusion = enabled;
        headerChanged = true;
        dirty = true;
    }

    public long layoutRevision() {
        return layoutRevision;
    }

    public long poseRevision() {
        return poseRevision;
    }

    public boolean isEmpty() {
        return staticData.length == 0;
    }

    public int staticWords() {
        return staticData.length;
    }

    public IntArrayList dynamicWords() {
        return new IntArrayList(dynamicData);
    }

    /**
     * Read-only until the next preparation; consumers copy the selected ranges synchronously.
     */
    public int[] preparedDynamicWords() {
        return dynamicData;
    }

    public void fillDynamicRanges(long previousRevision, IntArrayList ranges) {
        ranges.clear();
        if (previousRevision != poseRevision - 1) {
            ranges.add(0);
            ranges.add(dynamicData.length);
            return;
        }
        for (int start = changedWords.nextSetBit(0); start >= 0; ) {
            int end = changedWords.nextClearBit(start);
            int next;
            while ((next = changedWords.nextSetBit(end)) >= 0 && next - end <= 16) {
                end = changedWords.nextClearBit(next);
            }
            ranges.add(start);
            ranges.add(end);
            start = next;
        }
    }

    public void append(IntArrayList words) {
        words.addElements(words.size(), staticData);
        words.addElements(words.size(), dynamicData);
    }

    public void delete() {
        closed = true;
        for (Handle handle : instances) handle.deleted = true;
        for (Handle handle; (handle = pending.poll()) != null; ) handle.deleted = true;
        changed.set(null);
        changedIndices.clear();
        changedPoses.clear();
        touchedNodes.clear();
        markedNodes.clear();
        changedWords.clear();
        instances.clear();
        meshLookup.clear();
        meshes.clear();
        staticData = new int[0];
        dynamicData = new int[0];
        sceneNodes = new int[0];
        bounds = new float[0];
        topology = null;
    }

    private final class Handle implements Occluder {
        final OcclusionMesh source;
        final Matrix4f pose = new Matrix4f();
        final Matrix4f inverse = new Matrix4f();
        GeometryAoMesh mesh;
        int index = -1;
        boolean enqueued;
        Handle nextChanged;
        int x, y, z;
        float scaleU = 1, scaleV = 1, offsetU, offsetV, shearU, shearV, alpha = 1;
        float clipX, clipY, clipZ;
        float clipMin = Float.NEGATIVE_INFINITY, clipMax = Float.POSITIVE_INFINITY;
        boolean poseChanged = true, parametersChanged = true;
        boolean deleted;

        Handle(OcclusionMesh source) {
            this.source = source;
        }

        @Override
        public void transform(Vec3i anchor, Matrix4fc transform) {
            if (deleted || closed) throw new IllegalStateException();
            if (x == anchor.getX() && y == anchor.getY() && z == anchor.getZ() && pose.equals(transform)) return;
            if (transform.m03() != 0 || transform.m13() != 0 || transform.m23() != 0 || transform.m33() != 1)
                throw new IllegalArgumentException();
            float determinant = transform.determinant();
            if (determinant == 0 || !Float.isFinite(determinant) || !Float.isFinite(transform.m30())
                    || !Float.isFinite(transform.m31()) || !Float.isFinite(transform.m32()))
                throw new IllegalArgumentException();
            pose.set(transform);
            pose.invert(inverse);
            x = anchor.getX();
            y = anchor.getY();
            z = anchor.getZ();
            poseChanged = true;
            markDirty();
        }

        @Override
        public void delete() {
            if (deleted) return;
            deleted = true;
            markDirty();
        }

        @Override
        public void coverage(float scaleU, float shearU, float shearV, float scaleV,
                             float offsetU, float offsetV, float alpha) {
            if (deleted || closed) throw new IllegalStateException();
            if (!Float.isFinite(scaleU) || !Float.isFinite(scaleV) || !Float.isFinite(shearU)
                    || !Float.isFinite(shearV) || !Float.isFinite(offsetU)
                    || !Float.isFinite(offsetV) || !Float.isFinite(alpha) || alpha < 0 || alpha > 1)
                throw new IllegalArgumentException();
            if (this.scaleU == scaleU && this.scaleV == scaleV && this.offsetU == offsetU
                    && this.offsetV == offsetV && this.shearU == shearU && this.shearV == shearV && this.alpha == alpha)
                return;
            this.scaleU = scaleU;
            this.scaleV = scaleV;
            this.offsetU = offsetU;
            this.offsetV = offsetV;
            this.alpha = alpha;
            this.shearU = shearU;
            this.shearV = shearV;
            parametersChanged = true;
            markDirty();
        }

        @Override
        public void clip(float normalX, float normalY, float normalZ, float min, float max) {
            if (deleted || closed) throw new IllegalStateException();
            if (!Float.isFinite(normalX) || !Float.isFinite(normalY) || !Float.isFinite(normalZ) || Float.isNaN(
                    min) || Float.isNaN(max))
                throw new IllegalArgumentException();
            if (clipX == normalX && clipY == normalY && clipZ == normalZ && clipMin == min && clipMax == max) return;
            clipX = normalX;
            clipY = normalY;
            clipZ = normalZ;
            clipMin = min;
            clipMax = max;
            parametersChanged = true;
            markDirty();
        }

        private void markDirty() {
            if (!enqueued) {
                enqueued = true;
                Handle previous;
                do {
                    previous = changed.get();
                    nextChanged = previous;
                } while (!changed.compareAndSet(previous, this));
            }
            dirty = true;
        }

        void writePose(float[] bounds, int bound, int[] words, int at, Vector3f point, float[] matrix) {
            int dx = x - originX, dy = y - originY, dz = z - originZ;
            float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
            float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int corner = 0; corner < 8; corner++) {
                pose.transformPosition(Float.intBitsToFloat(mesh.nodes[(corner & 1) == 0 ? 0 : 4]),
                        Float.intBitsToFloat(mesh.nodes[(corner & 2) == 0 ? 1 : 5]),
                        Float.intBitsToFloat(mesh.nodes[(corner & 4) == 0 ? 2 : 6]), point);
                minX = Math.min(minX, point.x + dx);
                maxX = Math.max(maxX, point.x + dx);
                minY = Math.min(minY, point.y + dy);
                maxY = Math.max(maxY, point.y + dy);
                minZ = Math.min(minZ, point.z + dz);
                maxZ = Math.max(maxZ, point.z + dz);
            }
            bounds[bound] = minX;
            bounds[bound + 1] = minY;
            bounds[bound + 2] = minZ;
            bounds[bound + 3] = maxX;
            bounds[bound + 4] = maxY;
            bounds[bound + 5] = maxZ;
            words[at] = mesh.nodeOffset;
            words[at + 1] = mesh.nodes.length / OcclusionBvh.NODE_WORDS;
            words[at + 2] = mesh.triangleOffset;
            words[at + 3] = mesh.cutoutOffset;
            words[at + 4] = Float.floatToRawIntBits(dx);
            words[at + 5] = Float.floatToRawIntBits(dy);
            words[at + 6] = Float.floatToRawIntBits(dz);
            inverse.get(matrix);
            for (int i = 0; i < matrix.length; i++) words[at + 8 + i] = Float.floatToRawIntBits(matrix[i]);
        }

        void writeParameters(int[] words, int at) {
            words[at + 7] = clipMin != Float.NEGATIVE_INFINITY || clipMax != Float.POSITIVE_INFINITY ? 1 : 0;
            words[at + 32] = Float.floatToRawIntBits(clipX);
            words[at + 33] = Float.floatToRawIntBits(clipY);
            words[at + 34] = Float.floatToRawIntBits(clipZ);
            words[at + 35] = Float.floatToRawIntBits(clipMin);
            words[at + 36] = Float.floatToRawIntBits(clipMax);
            words[at + 24] = Float.floatToRawIntBits(offsetU);
            words[at + 25] = Float.floatToRawIntBits(offsetV);
            words[at + 26] = Float.floatToRawIntBits(scaleU);
            words[at + 27] = Float.floatToRawIntBits(scaleV);
            words[at + 28] = Float.floatToRawIntBits(alpha);
            words[at + 29] = Float.floatToRawIntBits(shearU);
            words[at + 30] = Float.floatToRawIntBits(shearV);
        }
    }
}
