package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.lib.model.FaceOccluder;
import dev.engine_room.flywheel.lib.model.MeshFaces;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

// Queue-driven: never walk every tracked instance per frame.
final class FaceCulling {
    private static final int MAX_DELAY = 32, QUIET = 2 * MAX_DELAY, WHEEL_MASK = 63;
    private final DrawManager<?> manager;
    private final LevelAccessor level;
    private final boolean enabled;
    private final ConcurrentLinkedQueue<Tracked<?>> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> dirtyChunks = new ConcurrentLinkedQueue<>();
    private final LongOpenHashSet invalidatedChunks = new LongOpenHashSet();
    private final IdentityHashMap<Instance, Tracked<?>> instances = new IdentityHashMap<>();
    private final IdentityHashMap<Model, Geometry> geometry = new IdentityHashMap<>();
    private final IdentityHashMap<EmbeddedEnvironment, Embedding> embeddings = new IdentityHashMap<>();
    private final Long2ObjectOpenHashMap<Set<Tracked<?>>> watchers = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Long2IntOpenHashMap> coverage = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
    private final Matrix4f pose = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final BitSet hidden = new BitSet();
    private final BitSet meshHidden = new BitSet();
    private final FaceOccluder oneSided = this::occludes;
    private final FaceOccluder twoSided = (x, y, z, facing) -> occludes(x, y, z, facing)
            && occludes(x - facing.getStepX(), y - facing.getStepY(), z - facing.getStepZ(), facing.getOpposite());
    private final Set<AbstractInstancer<?>> moved = Collections.newSetFromMap(new IdentityHashMap<>());
    @SuppressWarnings("unchecked")
    private final List<Tracked<?>>[] wheel = new List[WHEEL_MASK + 1];
    private Tracked<?> collecting;
    private long frame;

    FaceCulling(DrawManager<?> manager, LevelAccessor level) {
        this.manager = manager;
        this.level = level;
        enabled = !manager.isShaderPackGuest();
        for (int i = 0; i < wheel.length; i++) wheel[i] = new ArrayList<>();
    }

    private static boolean eligible(Model.ConfiguredMesh mesh) {
        var material = mesh.material();
        if (!material.shaders().preservesPosition() || material.polygonOffset()) return false;
        return switch (material.depthTest()) {
            case LESS, LEQUAL, EQUAL -> true;
            default -> false;
        };
    }

    // Any thread; previous entry released by its owner's next flush.
    <I extends Instance> void track(InstanceHandleImpl<?> handle, I instance, InstancerKey<I> key) {
        // View-dependent poses (billboards) land on a block plane only by coincidence, for one frame.
        var transform = key.type().transform();
        Tracked<?> entry = enabled && transform != null && !transform.viewDependent()
                && key.model().meshes().stream().anyMatch(FaceCulling::eligible) ? new Tracked<>(this, handle, instance,
                key) : null;
        var previous = handle.face;
        handle.face = entry;
        if (entry != null) entry.changed();
        if (previous != null) previous.changed();
    }

    void invalidate(int chunkX, int chunkZ) {
        if (enabled) dirtyChunks.add(ChunkPos.pack(chunkX, chunkZ));
    }

    void flush(Matrix4fc view, Vec3i origin) {
        invalidatedChunks.clear();
        for (Long chunk; (chunk = dirtyChunks.poll()) != null; ) {
            var key = chunk.longValue();
            if (!invalidatedChunks.add(key)) continue;
            coverage.remove(key);
            var affected = watchers.get(key);
            if (affected != null) for (var entry : affected) entry.changed();
        }
        this.view.set(view);
        for (var embedding : embeddings.values()) {
            if (embedding.pose.equals(embedding.environment.pose(), 0)) continue;
            embedding.pose.set(embedding.environment.pose());
            for (var entry : embedding.entries) entry.changed();
        }
        var due = wheel[(int) (++frame & WHEEL_MASK)];
        for (var entry : due) {
            entry.deferred = false;
            if (entry.dirty) evaluate(entry, origin);
            else entry.queued = false;
        }
        due.clear();
        for (Tracked<?> entry; (entry = queue.poll()) != null; ) {
            if (!entry.deferred) evaluate(entry, origin);
        }
        for (var instancer : moved) instancer.parallelUpdate();
        moved.clear();
    }

    // Nothing hidden + re-changed within QUIET frames => next check after 2x delay; changes meanwhile absorbed.
    private void evaluate(Tracked<?> entry, Vec3i origin) {
        entry.queued = false;
        entry.dirty = false;
        long gap = frame - entry.evaluated;
        entry.evaluated = frame;
        update(entry, origin);
        if (!entry.registered || entry.variant != null) {
            entry.delay = 0;
            return;
        }
        entry.delay = gap <= QUIET ? Math.min(MAX_DELAY, Math.max(1, entry.delay * 2)) : 0;
        if (entry.delay == 0) return;
        entry.queued = true;
        entry.deferred = true;
        wheel[(int) ((frame + entry.delay) & WHEEL_MASK)].add(entry);
    }

    private void update(Tracked<?> entry, Vec3i origin) {
        var handle = entry.handle;
        if (handle.face != entry || handle.state instanceof InstanceHandleImpl.Deleted<?>) {
            if (entry.registered && instances.remove(entry.instance, entry)) release(entry);
            return;
        }
        if (!entry.registered) register(entry);
        if (!handle.isVisible()) return;
        unwatch(entry);
        hidden.clear();
        entry.key.type().transform().write(handle.slabPtr(), view, pose);
        if (entry.key.environment() instanceof EmbeddedEnvironment embedded) pose.mulLocal(embedded.pose());
        if (pose.isAffine() && pose.isFinite()) {
            collecting = entry;
            int offset = 0;
            for (int i = 0; i < entry.geometry.faces.length; i++) {
                var faces = entry.geometry.faces[i];
                if (faces != null) {
                    var material = entry.key.model().meshes().get(i).material();
                    faces.hidden(pose, origin.getX(), origin.getY(), origin.getZ(),
                            material.backfaceCulling() ? oneSided : twoSided, meshHidden);
                    for (int bit = meshHidden.nextSetBit(0); bit >= 0; bit = meshHidden.nextSetBit(bit + 1))
                        hidden.set(offset + bit);
                    offset += faces.triangleCount();
                }
            }
            collecting = null;
        }
        if (hidden.isEmpty()) {
            releaseVariant(entry);
        } else if (entry.variant == null || !entry.variant.hidden.equals(hidden)) {
            releaseVariant(entry);
            entry.variant = entry.geometry.variants.computeIfAbsent((BitSet) hidden.clone(),
                    bits -> new Variant(bits, entry.geometry.view(bits)));
            entry.variant.users++;
        }
        var target = entry.variant == null ? entry.key.model() : entry.variant.model;
        if (target != entry.currentModel) move(entry, target);
    }

    private void register(Tracked<?> entry) {
        entry.registered = true;
        var previous = instances.put(entry.instance, entry);
        if (previous != null) release(previous);
        entry.geometry = geometry.computeIfAbsent(entry.key.model(), Geometry::new);
        entry.geometry.users++;
        if (entry.key.environment() instanceof EmbeddedEnvironment environment) {
            embeddings.computeIfAbsent(environment, Embedding::new).entries.add(entry);
        }
    }

    private <I extends Instance> void move(Tracked<I> entry, Model model) {
        var key = entry.key;
        moved.add(manager.getInstancer(key.environment(), key.type(), entry.currentModel, key.bias()));
        manager.getInstancer(key.environment(), key.type(), model, key.bias()).moveInstance(entry.instance);
        entry.currentModel = model;
    }

    private boolean occludes(int x, int y, int z, Direction facing) {
        if (level.isOutsideBuildHeight(y)) return false;
        long cell = BlockPos.asLong(x, y, z);
        long chunk = ChunkPos.pack(x >> 4, z >> 4);
        if (collecting.chunks.add(chunk))
            watchers.computeIfAbsent(chunk, _ -> Collections.newSetFromMap(new IdentityHashMap<>())).add(collecting);
        var cells = coverage.computeIfAbsent(chunk, _ -> new Long2IntOpenHashMap());
        int mask = cells.get(cell);
        int known = 1 << facing.ordinal(), covered = known << 6;
        if ((mask & known) == 0) {
            mask |= known;
            var loaded = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
            if (loaded != null) {
                var state = loaded.getBlockState(blockPos.set(x, y, z));
                if (state.canOcclude() && (state.isSolidRender()
                        || Block.isShapeFullBlock(state.getFaceOcclusionShape(facing.getOpposite())))) mask |= covered;
            }
            cells.put(cell, mask);
        }
        return (mask & covered) != 0;
    }

    private void unwatch(Tracked<?> entry) {
        for (long chunk : entry.chunks) {
            var entries = watchers.get(chunk);
            entries.remove(entry);
            if (entries.isEmpty()) {
                watchers.remove(chunk);
                coverage.remove(chunk);
            }
        }
        entry.chunks.clear();
    }

    private void releaseVariant(Tracked<?> entry) {
        if (entry.variant != null && --entry.variant.users == 0) entry.geometry.variants.remove(entry.variant.hidden);
        entry.variant = null;
    }

    private void release(Tracked<?> entry) {
        entry.registered = false;
        unwatch(entry);
        releaseVariant(entry);
        if (--entry.geometry.users == 0) geometry.remove(entry.key.model());
        if (entry.key.environment() instanceof EmbeddedEnvironment environment) {
            var embedding = embeddings.get(environment);
            embedding.entries.remove(entry);
            if (embedding.entries.isEmpty()) embeddings.remove(environment);
        }
    }

    // Live handles keep entries; next change re-registers them.
    void clear() {
        for (var due : wheel) {
            for (var entry : due) {
                entry.deferred = false;
                entry.queued = false;
                entry.delay = 0;
            }
            due.clear();
        }
        for (var entry : instances.values()) {
            entry.registered = false;
            entry.chunks.clear();
            entry.variant = null;
        }
        dirtyChunks.clear();
        instances.clear();
        geometry.clear();
        embeddings.clear();
        watchers.clear();
        coverage.clear();
    }

    static final class Tracked<I extends Instance> {
        final FaceCulling owner;
        final InstanceHandleImpl<?> handle;
        final I instance;
        final InstancerKey<I> key;
        final LongOpenHashSet chunks = new LongOpenHashSet();
        volatile boolean queued, dirty;
        boolean registered, deferred;
        int delay;
        long evaluated = -2 * MAX_DELAY;
        Geometry geometry;
        Variant variant;
        Model currentModel;

        Tracked(FaceCulling owner, InstanceHandleImpl<?> handle, I instance, InstancerKey<I> key) {
            this.owner = owner;
            this.handle = handle;
            this.instance = instance;
            this.key = key;
            currentModel = key.model();
        }

        // Any thread; a lost race only enqueues twice.
        void changed() {
            dirty = true;
            if (!queued) {
                queued = true;
                owner.queue.add(this);
            }
        }
    }

    private static final class Embedding {
        final EmbeddedEnvironment environment;
        final Matrix4f pose;
        final Set<Tracked<?>> entries = Collections.newSetFromMap(new IdentityHashMap<>());

        Embedding(EmbeddedEnvironment environment) {
            this.environment = environment;
            pose = new Matrix4f(environment.pose());
        }
    }

    private static final class Geometry {
        final Model model;
        final MeshFaces[] faces;
        final Map<BitSet, Variant> variants = new HashMap<>();
        int users;

        Geometry(Model model) {
            this.model = model;
            faces = new MeshFaces[model.meshes().size()];
            for (int i = 0; i < faces.length; i++) {
                var mesh = model.meshes().get(i);
                if (eligible(mesh)) faces[i] = MeshFaces.of(mesh.mesh());
            }
        }

        Model view(BitSet hidden) {
            var meshes = new ArrayList<Model.ConfiguredMesh>(faces.length);
            int offset = 0;
            for (int i = 0; i < faces.length; i++) {
                var source = model.meshes().get(i);
                var mesh = source.mesh();
                if (faces[i] != null) {
                    int count = faces[i].triangleCount();
                    mesh = faces[i].visible(mesh, hidden.get(offset, offset + count));
                    offset += count;
                }
                meshes.add(new Model.ConfiguredMesh(source.material(), mesh));
            }
            return new SimpleModel(meshes, model.boundingSphere());
        }
    }

    private static final class Variant {
        final BitSet hidden;
        final Model model;
        int users;

        Variant(BitSet hidden, Model model) {
            this.hidden = hidden;
            this.model = model;
        }
    }
}
