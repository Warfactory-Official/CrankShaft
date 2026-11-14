package dev.engine_room.flywheel.backend.engine.far;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.LightShaders;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static captured geometry shared by the active LoD host's terrain pages. Render thread only.
 *
 * <p>Compatible components are baked once per assembly and exact entry-relative poses. World anchor, tint, light and overlay live in
 * a small GPU instance record, so equal chests share their geometry. CPU vertex and instance payloads exist only
 * until {@link #flush()}; retained CPU state describes allocations, identities, materials and bounds.
 *
 * <p>Call {@code flush()} after draining captures and before a host consumes changes or binds these buffers.
 * Listeners receive incremental entry changes after the uploads finish. Both listener arguments are null on a
 * reset; a host registering later can bootstrap once from {@link #entries()}.
 */
public final class FarStaticMeshes {
    public static final int VERTEX_STRIDE = 48;
    public static final int INSTANCE_STRIDE = 32;
    public static final int INSTANCE_LIGHT_OFFSET = 20;
    public static final int TEMPLATE_STRIDE = 48;
    public static final int USE_OVERLAY = 1;
    public static final int USE_LIGHT = 2;
    public static final int BACKFACE_CULL = 4;
    public static final int CUTOUT_ONE_TENTH = 8;
    public static final int CARDINAL_ENTITY = 16;
    public static final int CARDINAL_CHUNK = 32;

    private final Uploads vertices = new Uploads();
    private final Uploads indices = new Uploads();
    private final Uploads instanceData = new Uploads();
    private final Uploads templateData = new Uploads();
    private final FarRanges vertexRanges = new FarRanges();
    private final FarRanges indexRanges = new FarRanges();
    private final Map<TemplateKey, LiveTemplate> templateKeys = new HashMap<>();
    private final Int2ObjectOpenHashMap<LiveTemplate> templates = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Instance> instances = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final IntArrayList freeTemplates = new IntArrayList();
    private final IntArrayList freeInstances = new IntArrayList();
    private final List<Listener> listeners = new ArrayList<>();
    private final Long2ObjectOpenHashMap<Change> changes = new Long2ObjectOpenHashMap<>();
    private int nextTemplate;
    private int nextInstance;
    private long generation;
    private boolean resetPending;

    public FarStaticMeshes() {
    }

    public FarStaticMeshes(Listener listener) {
        addListener(listener);
    }

    /**
     * All supplied rows can be represented faithfully by the static terrain path.
     */
    public static boolean eligible(List<FarRow> rows) {
        if (rows.isEmpty()) return false;
        boolean geometry = false;
        for (var row : rows) {
            if (!eligible(row)) return false;
            for (var configured : row.snapshot().model().meshes()) {
                geometry |= configured.mesh().vertexCount() != 0 && configured.mesh().indexCount() != 0;
            }
        }
        return geometry;
    }

    /**
     * The capture's declared motion, rather than its visual class, determines whether a row is static.
     */
    public static boolean eligible(FarRow row) {
        var type = row.snapshot().type();
        if (!row.snapshot().terrain() || row.radiansPerTick() != 0
                || type != InstanceTypes.TRANSFORMED && type != InstanceTypes.POSED) {
            return false;
        }
        for (var configured : row.snapshot().model().meshes()) {
            if (!eligible(configured.material())) return false;
        }
        return true;
    }

    private static boolean eligible(Material material) {
        var shaders = material.shaders();
        var cutout = material.cutout().source();
        var light = material.light().source();
        return material.transparency() == Transparency.OPAQUE && material.depthTest() == DepthTest.LEQUAL
                && material.writeMask() == WriteMask.COLOR_DEPTH && !material.polygonOffset()
                && shaders.vertexSource().equals(StandardMaterialShaders.DEFAULT.vertexSource())
                && shaders.fragmentSource().equals(StandardMaterialShaders.DEFAULT.fragmentSource())
                && (cutout.equals(CutoutShaders.OFF.source()) || cutout.equals(CutoutShaders.ONE_TENTH.source()))
                && (light.equals(LightShaders.SMOOTH_WHEN_EMBEDDED.source())
                || light.equals(LightShaders.FLAT.source()) || light.equals(LightShaders.NONE.source()));
    }

    /**
     * Replaces one entry, or returns false without adopting any rows when its geometry needs another path.
     * GPU uploads and listener delivery are batched until {@link #flush()}.
     */
    public boolean add(long pos, Vec3i origin, List<FarRow> rows, int light) {
        if (!eligible(rows)) return false;
        Entry previous = entries.get(pos);
        if (previous != null) release(previous);
        long version = ++generation;
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        var captured = new ArrayList<Instance>();
        var groups = new ArrayList<Group>();
        Bounds bounds = null;
        for (var row : rows) {
            var snapshot = row.snapshot();
            var data = ByteBuffer.wrap(snapshot.data()).order(ByteOrder.nativeOrder());
            var pose = new Matrix4f(
                    data.getFloat(12), data.getFloat(16), data.getFloat(20), data.getFloat(24),
                    data.getFloat(28), data.getFloat(32), data.getFloat(36), data.getFloat(40),
                    data.getFloat(44), data.getFloat(48), data.getFloat(52), data.getFloat(56),
                    data.getFloat(60), data.getFloat(64), data.getFloat(68), data.getFloat(72));
            Matrix3f normal;
            if (snapshot.type() == InstanceTypes.POSED) {
                normal = new Matrix3f(
                        data.getFloat(76), data.getFloat(80), data.getFloat(84),
                        data.getFloat(88), data.getFloat(92), data.getFloat(96),
                        data.getFloat(100), data.getFloat(104), data.getFloat(108));
            } else {
                normal = new Matrix3f(pose).invert().transpose();
            }
            snapshot.pose().mul(pose, pose);
            snapshot.normal().mul(normal, normal);
            pose.m30(pose.m30() - (x - origin.getX()));
            pose.m31(pose.m31() - (y - origin.getY()));
            pose.m32(pose.m32() - (z - origin.getZ()));
            var poseKey = new PoseKey(pose, normal);
            for (var configured : snapshot.model().meshes()) {
                var mesh = configured.mesh();
                if (mesh.vertexCount() == 0 || mesh.indexCount() == 0) continue;
                var appearance = new Appearance(configured.material(), data.getInt(0),
                        maxLight(light, snapshot.light()),
                        data.getInt(4), FarMaterials.resolve(snapshot.model(), configured.material(), mesh,
                        snapshot.fallbackState()), snapshot.bias());
                // Keep capture order: merging across a different appearance could reorder coplanar surfaces.
                if (groups.isEmpty() || !groups.getLast().appearance.equals(appearance)) {
                    groups.add(new Group(appearance));
                }
                groups.getLast().components.add(new Component(mesh, pose, normal, new ComponentKey(mesh, poseKey)));
            }
        }
        for (var group : groups) {
            var appearance = group.appearance;
            var key = new TemplateKey(appearance.material, group.components.stream().map(Component::key).toList());
            var template = templateKeys.get(key);
            if (template == null) template = bake(key, group.components);
            template.references++;
            int slot = freeInstances.isEmpty() ? nextInstance++ : freeInstances.popInt();
            Bounds worldBounds = template.metadata.bounds.translate(x, y, z);
            var instance = new Instance(slot, version, template.metadata.id, pos, worldBounds);
            instances.put(slot, instance);
            captured.add(instance);
            bounds = bounds == null ? worldBounds : bounds.union(worldBounds);
            long target = instanceData.allocate((long) slot * INSTANCE_STRIDE, INSTANCE_STRIDE);
            MemoryUtil.memPutInt(target, x);
            MemoryUtil.memPutInt(target + 4, y);
            MemoryUtil.memPutInt(target + 8, z);
            MemoryUtil.memPutInt(target + 12, template.metadata.id);
            MemoryUtil.memPutInt(target + 16, appearance.color);
            MemoryUtil.memPutInt(target + INSTANCE_LIGHT_OFFSET, appearance.light);
            MemoryUtil.memPutInt(target + 24, appearance.overlay);
            MemoryUtil.memPutInt(target + 28, appearance.materialId);
        }
        var next = new Entry(pos, version, List.copyOf(captured), bounds);
        entries.put(pos, next);
        changed(pos, previous, next);
        return true;
    }

    public void remove(long pos) {
        Entry previous = entries.remove(pos);
        if (previous == null) return;
        release(previous);
        changed(pos, previous, null);
    }

    private void changed(long pos, @Nullable Entry previous, @Nullable Entry next) {
        var pending = changes.get(pos);
        if (pending != null) previous = pending.previous;
        if (previous == null && next == null) {
            changes.remove(pos);
        } else {
            changes.put(pos, new Change(previous, next));
        }
    }

    private void release(Entry entry) {
        for (var instance : entry.instances) {
            instances.remove(instance.slot);
            freeInstances.add(instance.slot);
            var template = templates.get(instance.templateId);
            if (--template.references == 0) {
                var metadata = template.metadata;
                templates.remove(metadata.id);
                templateKeys.remove(template.key);
                freeTemplates.add(metadata.id);
                vertexRanges.free(metadata.firstVertex, metadata.vertexCount);
                indexRanges.free(metadata.firstIndex, template.indexAllocationCount);
            }
        }
    }

    /**
     * Uploads contiguous capture batches, then publishes the corresponding entry changes.
     */
    public void flush() {
        vertices.flush();
        indices.flush();
        instanceData.flush();
        templateData.flush();
        if (resetPending) {
            for (var listener : listeners) listener.changed(null, null);
            resetPending = false;
        }
        for (var change : changes.values()) {
            for (var listener : listeners) listener.changed(change.previous, change.next);
        }
        changes.clear();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean contains(long pos) {
        return entries.containsKey(pos);
    }

    public @Nullable Entry entry(long pos) {
        return entries.get(pos);
    }

    public @Nullable Instance instance(int slot) {
        return instances.get(slot);
    }

    public Template template(int id) {
        return templates.get(id).metadata;
    }

    /**
     * Immutable bootstrap metadata; hosts consume incremental listeners after their initial attachment.
     */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public List<Template> templates() {
        return templates.values().stream().map(template -> template.metadata).toList();
    }

    public int size() {
        return entries.size();
    }

    public int instanceCount() {
        return instances.size();
    }

    public int templateCount() {
        return templates.size();
    }

    public int vertexBuffer() {
        return vertices.buffer.handle();
    }

    public int indexBuffer() {
        return indices.buffer.handle();
    }

    public int instanceBuffer() {
        return instanceData.buffer.handle();
    }

    public int templateBuffer() {
        return templateData.buffer.handle();
    }

    public void clear() {
        entries.clear();
        instances.clear();
        templates.clear();
        templateKeys.clear();
        freeInstances.clear();
        freeTemplates.clear();
        nextInstance = nextTemplate = 0;
        vertexRanges.clear();
        indexRanges.clear();
        vertices.discard();
        indices.discard();
        instanceData.discard();
        templateData.discard();
        changes.clear();
        resetPending = true;
    }

    public void delete() {
        clear();
        flush();
        vertices.buffer.delete();
        indices.buffer.delete();
        instanceData.buffer.delete();
        templateData.buffer.delete();
        listeners.clear();
    }

    private LiveTemplate bake(TemplateKey key, List<Component> components) {
        int id = freeTemplates.isEmpty() ? nextTemplate++ : freeTemplates.popInt();
        int vertexCount = 0, indexCount = 0, largestComponent = 0;
        for (var component : components) {
            int count = component.mesh.vertexCount();
            vertexCount += count;
            indexCount += component.mesh.indexCount();
            largestComponent = Math.max(largestComponent, count);
        }
        // Every triangle belongs to one directional group and appears in half of the 64 masks.
        int indexAllocationCount = key.material.backfaceCulling() ? indexCount * 32 : indexCount;
        int firstVertex = vertexRanges.allocate(vertexCount), firstIndex = indexRanges.allocate(indexAllocationCount);
        long target = vertices.allocate((long) firstVertex * VERTEX_STRIDE, (long) vertexCount * VERTEX_STRIDE);
        long indexTarget = indices.allocate((long) firstIndex * Integer.BYTES,
                (long) indexAllocationCount * Integer.BYTES);
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        MemoryBlock source = MemoryBlock.malloc(largestComponent * FullVertexView.STRIDE);
        try {
            var view = new FullVertexView();
            view.ptr(source.ptr());
            var position = new Vector3f();
            var normal = new Vector3f();
            int baseVertex = 0, baseIndex = 0;
            for (var component : components) {
                int count = component.mesh.vertexCount();
                view.vertexCount(count);
                component.mesh.write(view);
                for (int vertex = 0; vertex < count; vertex++) {
                    component.pose.transformPosition(view.x(vertex), view.y(vertex), view.z(vertex), position);
                    component.normal.transform(view.normalX(vertex), view.normalY(vertex), view.normalZ(vertex),
                            normal);
                    minX = Math.min(minX, position.x);
                    minY = Math.min(minY, position.y);
                    minZ = Math.min(minZ, position.z);
                    maxX = Math.max(maxX, position.x);
                    maxY = Math.max(maxY, position.y);
                    maxZ = Math.max(maxZ, position.z);
                    long at = target + (long) (baseVertex + vertex) * VERTEX_STRIDE;
                    MemoryUtil.memPutFloat(at, position.x);
                    MemoryUtil.memPutFloat(at + 4, position.y);
                    MemoryUtil.memPutFloat(at + 8, position.z);
                    MemoryUtil.memPutInt(at + 12,
                            MemoryUtil.memGetInt(source.ptr() + vertex * FullVertexView.STRIDE + 12));
                    MemoryUtil.memPutFloat(at + 16, view.u(vertex));
                    MemoryUtil.memPutFloat(at + 20, view.v(vertex));
                    MemoryUtil.memPutInt(at + 24, view.overlay(vertex));
                    MemoryUtil.memPutInt(at + 28, view.light(vertex));
                    MemoryUtil.memPutFloat(at + 32, normal.x);
                    MemoryUtil.memPutFloat(at + 36, normal.y);
                    MemoryUtil.memPutFloat(at + 40, normal.z);
                    MemoryUtil.memPutInt(at + 44, 0);
                }
                int componentIndices = component.mesh.indexCount();
                long componentTarget = indexTarget + (long) baseIndex * Integer.BYTES;
                component.mesh.indexSequence().fill(componentTarget, componentIndices);
                for (int index = 0; index < componentIndices; index++) {
                    long at = componentTarget + (long) index * Integer.BYTES;
                    MemoryUtil.memPutInt(at, MemoryUtil.memGetInt(at) + baseVertex);
                }
                baseVertex += count;
                baseIndex += componentIndices;
            }
        } finally {
            source.free();
        }
        var bounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        var variants = directionalIndices(target, indexTarget, firstIndex, indexCount, key.material.backfaceCulling());
        int textureSlot = BindlessSlots.slot(key.material);
        var metadata = new Template(id, firstVertex, vertexCount, firstIndex, indexCount, key.material, bounds,
                textureSlot, variants.ranges, variants.normalBounds, variants.normalIndexCounts,
                variants.normalPlaneOffsets);
        long descriptor = templateData.allocate((long) id * TEMPLATE_STRIDE, TEMPLATE_STRIDE);
        MemoryUtil.memPutInt(descriptor, firstIndex);
        MemoryUtil.memPutInt(descriptor + 4, indexCount);
        MemoryUtil.memPutInt(descriptor + 8, firstVertex);
        MemoryUtil.memPutInt(descriptor + 12, vertexCount);
        MemoryUtil.memPutFloat(descriptor + 16, minX);
        MemoryUtil.memPutFloat(descriptor + 20, minY);
        MemoryUtil.memPutFloat(descriptor + 24, minZ);
        MemoryUtil.memPutInt(descriptor + 28, flags(key.material));
        MemoryUtil.memPutFloat(descriptor + 32, maxX);
        MemoryUtil.memPutFloat(descriptor + 36, maxY);
        MemoryUtil.memPutFloat(descriptor + 40, maxZ);
        MemoryUtil.memPutInt(descriptor + 44, textureSlot);
        var result = new LiveTemplate(key, metadata, indexAllocationCount);
        templateKeys.put(key, result);
        templates.put(id, result);
        return result;
    }

    private static DirectionalIndices directionalIndices(long vertexPtr, long indexPtr, int firstIndex,
                                                         int indexCount, boolean culling) {
        var triangleGroups = new byte[indexCount / 3];
        var normalBounds = new float[36];
        var normalIndexCounts = new int[6];
        var normalPlaneOffsets = new float[6];
        Arrays.fill(normalPlaneOffsets, Float.POSITIVE_INFINITY);
        for (int group = 0; group < 6; group++) {
            Arrays.fill(normalBounds, group * 6, group * 6 + 3, Float.POSITIVE_INFINITY);
            Arrays.fill(normalBounds, group * 6 + 3, group * 6 + 6, Float.NEGATIVE_INFINITY);
        }
        for (int triangle = 0; triangle < triangleGroups.length; triangle++) {
            long source = indexPtr + (long) triangle * 3 * Integer.BYTES;
            long a = vertexPtr + (long) MemoryUtil.memGetInt(source) * VERTEX_STRIDE;
            long b = vertexPtr + (long) MemoryUtil.memGetInt(source + 4) * VERTEX_STRIDE;
            long c = vertexPtr + (long) MemoryUtil.memGetInt(source + 8) * VERTEX_STRIDE;
            double ax = MemoryUtil.memGetFloat(a), ay = MemoryUtil.memGetFloat(a + 4), az = MemoryUtil.memGetFloat(
                    a + 8);
            double ux = MemoryUtil.memGetFloat(b) - ax;
            double uy = MemoryUtil.memGetFloat(b + 4) - ay;
            double uz = MemoryUtil.memGetFloat(b + 8) - az;
            double vx = MemoryUtil.memGetFloat(c) - ax;
            double vy = MemoryUtil.memGetFloat(c + 4) - ay;
            double vz = MemoryUtil.memGetFloat(c + 8) - az;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            // A zero-area triangle has no facing direction; keep its zero normal instead of normalizing to NaNs.
            if (length != 0) {
                nx /= length;
                ny /= length;
                nz /= length;
            }
            int axis = Math.abs(ny) > Math.abs(nx) ? 1 : 0;
            if (Math.abs(nz) > Math.max(Math.abs(nx), Math.abs(ny))) axis = 2;
            double dominant = axis == 0 ? nx : axis == 1 ? ny : nz;
            int group = axis * 2 + (dominant < 0 ? 1 : 0);
            triangleGroups[triangle] = (byte) group;
            normalIndexCounts[group] += 3;
            int at = group * 6;
            normalBounds[at] = Math.min(normalBounds[at], Math.nextDown((float) nx));
            normalBounds[at + 1] = Math.min(normalBounds[at + 1], Math.nextDown((float) ny));
            normalBounds[at + 2] = Math.min(normalBounds[at + 2], Math.nextDown((float) nz));
            normalBounds[at + 3] = Math.max(normalBounds[at + 3], Math.nextUp((float) nx));
            normalBounds[at + 4] = Math.max(normalBounds[at + 4], Math.nextUp((float) ny));
            normalBounds[at + 5] = Math.max(normalBounds[at + 5], Math.nextUp((float) nz));
            double planeOffset = nx * ax + ny * ay + nz * az;
            normalPlaneOffsets[group] = Math.min(normalPlaneOffsets[group], Math.nextDown((float) planeOffset));
        }
        var ranges = new int[128];
        int written = indexCount;
        for (int mask = 0; mask < 64; mask++) {
            if (!culling || mask == 63) {
                ranges[mask * 2] = firstIndex;
                ranges[mask * 2 + 1] = indexCount;
                continue;
            }
            int start = written;
            for (int triangle = 0; triangle < triangleGroups.length; triangle++) {
                if ((mask & 1 << triangleGroups[triangle]) == 0) continue;
                MemoryUtil.memCopy(indexPtr + (long) triangle * 3 * Integer.BYTES,
                        indexPtr + (long) written * Integer.BYTES, 3L * Integer.BYTES);
                written += 3;
            }
            ranges[mask * 2] = firstIndex + start;
            ranges[mask * 2 + 1] = written - start;
        }
        return new DirectionalIndices(ranges, normalBounds, normalIndexCounts, normalPlaneOffsets);
    }

    private static int maxLight(int a, int b) {
        return Math.max(a & 0xFFFF, b & 0xFFFF) | Math.max(a >>> 16, b >>> 16) << 16;
    }

    public static int flags(Material material) {
        return (material.useOverlay() ? USE_OVERLAY : 0) | (material.useLight() ? USE_LIGHT : 0)
                | (material.backfaceCulling() ? BACKFACE_CULL : 0)
                | (material.cutout().source().equals(CutoutShaders.ONE_TENTH.source()) ? CUTOUT_ONE_TENTH : 0)
                | switch (material.cardinalLightingMode()) {
            case ENTITY -> CARDINAL_ENTITY;
            case CHUNK -> CARDINAL_CHUNK;
            case OFF -> 0;
        };
    }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Bounds translate(double x, double y, double z) {
            return new Bounds(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
        }

        public Bounds union(Bounds other) {
            return new Bounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }
    }

    /**
     * Indices are assembly-local; an indexed draw uses {@code firstIndex} and base vertex {@code firstVertex}.
     * Metadata arrays are read-only. Each normal group has six bounds, min XYZ followed by max XYZ; groups with
     * {@code normalIndexCounts[group] == 0} have no normal bounds. Mask 63 always names the original full mesh.
     * Plane offsets are minima of dot(unit geometric normal, local triangle vertex), rounded down.
     */
    public record Template(int id, int firstVertex, int vertexCount, int firstIndex, int indexCount,
                           Material material, Bounds bounds, int textureSlot, int[] variantRanges,
                           float[] normalBounds, int[] normalIndexCounts, float[] normalPlaneOffsets) {
        /**
         * Mask bits are +X, -X, +Y, -Y, +Z, -Z. Every variant keeps the original triangle order.
         */
        public IndexVariant variant(int mask) {
            return new IndexVariant(variantRanges[mask * 2], variantRanges[mask * 2 + 1]);
        }
    }

    public record IndexVariant(int firstIndex, int indexCount) {
    }

    /**
     * Bounds are world-space metadata; the appearance and anchor are stored in the GPU instance record.
     */
    public record Instance(int slot, long generation, int templateId, long pos, Bounds bounds) {
    }

    public record Entry(long pos, long generation, List<Instance> instances, Bounds bounds) {
    }

    @FunctionalInterface
    public interface Listener {
        /**
         * A null end denotes add/remove; both null denotes a complete reset. Runs on the render thread at flush.
         */
        void changed(@Nullable Entry previous, @Nullable Entry next);

    }

    private record Change(@Nullable Entry previous, @Nullable Entry next) {
    }

    private record TemplateKey(Material material, List<ComponentKey> components) {
    }

    private record ComponentKey(Mesh mesh, PoseKey pose) {
    }

    private record Component(Mesh mesh, Matrix4f pose, Matrix3f normal, ComponentKey key) {
    }

    private record Appearance(Material material, int color, int light, int overlay, int materialId, int bias) {
    }

    private record DirectionalIndices(int[] ranges, float[] normalBounds, int[] normalIndexCounts,
                                      float[] normalPlaneOffsets) {
    }

    private static final class Group {
        private final Appearance appearance;
        private final List<Component> components = new ArrayList<>();

        private Group(Appearance appearance) {
            this.appearance = appearance;
        }
    }

    private static final class PoseKey {
        private final float[] values = new float[25];
        private final int hash;

        private PoseKey(Matrix4f pose, Matrix3f normal) {
            pose.get(values);
            float[] normalValues = new float[9];
            normal.get(normalValues);
            System.arraycopy(normalValues, 0, values, 16, 9);
            hash = Arrays.hashCode(values);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PoseKey key && Arrays.equals(values, key.values);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class LiveTemplate {
        private final TemplateKey key;
        private final Template metadata;
        private final int indexAllocationCount;
        private int references;

        private LiveTemplate(TemplateKey key, Template metadata, int indexAllocationCount) {
            this.key = key;
            this.metadata = metadata;
            this.indexAllocationCount = indexAllocationCount;
        }
    }

    private static final class Uploads {
        private final FarBuffer buffer = new FarBuffer();
        private final List<Span> spans = new ArrayList<>();
        private @Nullable MemoryBlock staging;
        private long used;
        private long required;

        private long allocate(long offset, long bytes) {
            long next = used + bytes;
            if (staging == null) {
                staging = MemoryBlock.malloc(Math.max(next, 256));
            } else if (next > staging.size()) {
                staging = staging.realloc(Math.max(next, staging.size() * 2));
            }
            var last = spans.isEmpty() ? null : spans.getLast();
            if (last != null && last.offset + last.bytes == offset) {
                last.bytes += bytes;
            } else {
                spans.add(new Span(offset, used, bytes));
            }
            long result = staging.ptr() + used;
            used = next;
            required = Math.max(required, offset + bytes);
            return result;
        }

        private void flush() {
            if (staging == null) return;
            buffer.ensure(required);
            for (var span : spans) buffer.write(span.offset, staging.ptr() + span.source, span.bytes);
            discard();
        }

        private void discard() {
            if (staging != null) {
                staging.free();
                staging = null;
            }
            spans.clear();
            used = required = 0;
        }
    }

    private static final class Span {
        private final long offset;
        private final long source;
        private long bytes;

        private Span(long offset, long source, long bytes) {
            this.offset = offset;
            this.source = source;
            this.bytes = bytes;
        }
    }
}
