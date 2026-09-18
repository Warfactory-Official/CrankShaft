package dev.engine_room.flywheel.backend.lighting;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shared occlusion data for a client world. This class owns no drawable surfaces or render instances.
 * Visuals own their occluder handles; receiver subscriptions request compiled terrain around them.
 * Create, prepare and close on the render thread after visual updates join. A registered barrier also
 * joins outstanding updates during world teardown.
 */
public final class WorldLighting {
    private static @Nullable WorldLighting current;
    private final ClientLevel level;
    private final GeometryAoStorage geometry = new GeometryAoStorage();
    private final TerrainGeometryCache terrain;
    private final ConcurrentLinkedQueue<Subscription> additions = new ConcurrentLinkedQueue<>();
    private final ArrayList<Subscription> subscriptions = new ArrayList<>();
    private final LongSet sections = new LongOpenHashSet();
    private volatile boolean sectionsDirty;
    private boolean closed;

    private WorldLighting(ClientLevel level) {
        this.level = level;
        terrain = new TerrainGeometryCache(level, geometry);
    }

    public static WorldLighting of(ClientLevel level) {
        if (current == null || current.level != level) {
            reset();
            current = new WorldLighting(level);
        }
        return current;
    }

    public static void reset() {
        if (current == null) return;
        current.close();
        current = null;
    }

    public GeometryAoStorage geometry() {
        return geometry;
    }

    public Subscription subscribe() {
        if (closed) throw new IllegalStateException();
        var subscription = new Subscription();
        additions.add(subscription);
        sectionsDirty = true;
        return subscription;
    }

    public void prepare() {
        for (Subscription subscription; (subscription = additions.poll()) != null; ) subscriptions.add(subscription);
        if (sectionsDirty) {
            sectionsDirty = false;
            sections.clear();
            subscriptions.removeIf(subscription -> subscription.closed);
            for (Subscription subscription : subscriptions) sections.addAll(subscription.sections);
            terrain.sections(sections);
        }
        terrain.applyUpdates();
        terrain.flushRequests();
        var camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.blockPos;
        geometry.ambientOcclusion(Minecraft.getInstance().options.ambientOcclusion().get());
        geometry.prepare(new BlockPos(Math.floorDiv(camera.getX(), 64) * 64,
                Math.floorDiv(camera.getY(), 64) * 64, Math.floorDiv(camera.getZ(), 64) * 64));
    }

    private void close() {
        for (Subscription subscription; (subscription = additions.poll()) != null; ) subscriptions.add(subscription);
        for (Subscription subscription : subscriptions) {
            Runnable barrier = subscription.barrier;
            if (!subscription.closed && barrier != null) barrier.run();
        }
        closed = true;
        terrain.close();
        geometry.delete();
        subscriptions.clear();
        additions.clear();
        sections.clear();
    }

    /**
     * One visual-manager subscription; sections and the optional join callback are updated before preparation.
     */
    public final class Subscription implements AutoCloseable {
        private volatile LongSet sections = LongSet.of();
        private volatile @Nullable Runnable barrier;
        private volatile boolean closed;

        public void sections(LongSet sections) {
            if (closed || WorldLighting.this.closed) throw new IllegalStateException();
            this.sections = new LongOpenHashSet(sections);
            sectionsDirty = true;
        }

        public void barrier(@Nullable Runnable barrier) {
            this.barrier = barrier;
        }

        @Override
        public void close() {
            closed = true;
            sectionsDirty = true;
        }
    }
}
