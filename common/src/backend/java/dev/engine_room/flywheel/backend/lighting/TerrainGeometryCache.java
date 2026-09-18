package dev.engine_room.flywheel.backend.lighting;

import dev.engine_room.flywheel.api.lighting.GeometryOcclusion;
import dev.engine_room.flywheel.api.lighting.OcclusionMesh;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ConcurrentLinkedQueue;

public final class TerrainGeometryCache {    private static volatile @Nullable TerrainGeometryCache active;

    private static final Matrix4f IDENTITY = new Matrix4f();
    private final ClientLevel level;
    private final GeometryAoStorage scene;
    private volatile LongSet wanted = LongSet.of();
    private final ConcurrentLinkedQueue<Object> updates = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> requests = new ConcurrentLinkedQueue<>();
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();

    public TerrainGeometryCache(ClientLevel level, GeometryAoStorage scene) {
        this.level = level;
        this.scene = scene;
        active = this;
    }

    public static boolean wants(ClientLevel level, long section) {
        var cache = active;
        return cache != null && cache.level == level && cache.wanted.contains(section);
    }

    public static boolean isActive(ClientLevel level) {
        var cache = active;
        return cache != null && cache.level == level && !cache.wanted.isEmpty();
    }

    public static @Nullable Capture begin(ClientLevel level, long section) {
        var cache = active;
        return cache != null && cache.level == level && cache.wanted.contains(section) ? new Capture(cache,
                section) : null;
    }

    static @Nullable Capture beginMeshing(long section) {
        var cache = active;
        return cache != null && cache.wanted.contains(section) ? new Capture(cache, section) : null;
    }

    public static void remove(Object owner, long section) {
        var cache = active;
        if (cache != null) cache.updates.add(new Removal(owner, section));
    }

    public static void request(ClientLevel level, long section) {
        var cache = active;
        if (cache != null && cache.level == level && cache.wanted.contains(section)) cache.requests.add(section);
    }

    public static void invalidate(ClientLevel level) {
        var cache = active;
        if (cache == null || cache.level != level) return;
        for (long section : cache.wanted) cache.requests.add(section);
    }

    public void sections(LongSet sections) {
        var next = new LongOpenHashSet();
        for (long section : sections) {
            int sx = SectionPos.x(section), sy = SectionPos.y(section), sz = SectionPos.z(section);
            for (int y = sy - 1; y <= sy + 1; y++) {
                if (y < level.getMinSectionY() || y > level.getMaxSectionY()) continue;
                for (int z = sz - 1; z <= sz + 1; z++)
                    for (int x = sx - 1; x <= sx + 1; x++) next.add(SectionPos.asLong(x, y, z));
            }
        }
        LongSet previous = wanted;
        wanted = next;
        var iterator = entries.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!next.contains(entry.getLongKey())) {
                entry.getValue().delete();
                iterator.remove();
            }
        }
        for (long section : next) if (!previous.contains(section)) requests.add(section);
    }

    public void flushRequests() {
        if (active != this || Minecraft.getInstance().level != level) return;
        if (LightingCompatibility.SODIUM) {
            for (Entry entry : entries.values()) SodiumTerrainGeometry.activate(entry.sprites);
        }
        if (requests.isEmpty()) return;
        var unique = new LongOpenHashSet();
        for (Long section; (section = requests.poll()) != null; ) unique.add(section.longValue());
        var regions = new RenderRegionCache();
        for (long section : unique) {
            if (!wanted.contains(section) || !level.getChunkSource()
                                                   .hasChunk(SectionPos.x(section), SectionPos.z(section))) continue;
            if (LightingCompatibility.SODIUM) {
                SodiumTerrainGeometry.request(section);
            } else {
                var view = Minecraft.getInstance().levelRenderer.viewArea();
                if (view == null) continue;
                var renderSection = view.getRenderSectionAt(SectionPos.of(section).origin());
                if (renderSection != null) renderSection.compileAsync(regions.createRegion(level, section));
            }
        }
    }

    public void applyUpdates() {
        for (Object update; (update = updates.poll()) != null; ) {
            if (update instanceof Removal(Object owner, long section)) {
                Entry entry = entries.get(section);
                if (entry != null && entry.owner == owner) {
                    entries.remove(section);
                    entry.delete();
                }
                continue;
            }
            Capture capture = (Capture) update;
            OcclusionMesh[] meshes = capture.meshes;
            capture.meshes = null;
            if (capture.closed || !wanted.contains(capture.section)) continue;
            Entry previous = entries.get(capture.section);
            if (previous != null && previous.same(meshes)) {
                previous.owner = capture.owner;
                previous.sprites = capture.sprites;
                continue;
            }
            if (previous != null) previous.delete();
            var handles = new GeometryOcclusion.Occluder[meshes.length];
            var anchor = SectionPos.of(capture.section).origin();
            for (int i = 0; i < meshes.length; i++) handles[i] = scene.add(meshes[i], anchor, IDENTITY);
            entries.put(capture.section, new Entry(capture.owner, meshes, handles, capture.sprites));
        }
    }

    public void close() {
        if (active == this) active = null;
        wanted = LongSet.of();
        for (Entry entry : entries.values()) entry.delete();
        entries.clear();
        updates.clear();
        requests.clear();
    }

    public static final class Capture {        private final TerrainGeometryCache cache;

        public final long section;
        private Object owner;
        private OcclusionMesh[] meshes;
        private TextureAtlasSprite[] sprites = new TextureAtlasSprite[0];
        private volatile boolean closed;
        private boolean published;

        private Capture(TerrainGeometryCache cache, long section) {
            this.cache = cache;
            this.section = section;
        }

        public void complete(Object owner, OcclusionMesh[] meshes) {
            this.owner = owner;
            this.meshes = meshes;
        }

        public void sprites(TextureAtlasSprite[] sprites) {
            this.sprites = sprites;
        }

        boolean accepts(ClientLevel level) {
            return active == cache && cache.level == level && cache.wanted.contains(section);
        }

        public void publish() {
            if (!published && !closed && active == cache) {
                published = true;
                cache.updates.add(this);
            }
        }

        public void close() {
            closed = true;
            cache.updates.add(new Removal(owner, section));
        }
    }

    private record Removal(Object owner, long section) {
    }

    private static final class Entry {        Object owner;

        final OcclusionMesh[] meshes;
        final GeometryOcclusion.Occluder[] handles;
        TextureAtlasSprite[] sprites;

        Entry(Object owner, OcclusionMesh[] meshes, GeometryOcclusion.Occluder[] handles,
              TextureAtlasSprite[] sprites) {
            this.owner = owner;
            this.meshes = meshes;
            this.handles = handles;
            this.sprites = sprites;
        }

        boolean same(OcclusionMesh[] other) {
            if (other.length != meshes.length) return false;
            for (int i = 0; i < other.length; i++) if (!meshes[i].sameGeometry(other[i])) return false;
            return true;
        }

        void delete() {
            for (var handle : handles) handle.delete();
        }
    }
}
