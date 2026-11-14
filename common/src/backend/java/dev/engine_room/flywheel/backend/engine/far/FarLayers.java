package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.FarPrograms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.function.Consumer;

/**
 * Layered atlas and bounded raw bake leases. Render thread; raw records are never OIT-resolved here.
 */
final class FarLayers implements AutoCloseable {
    static final int NODE_BYTES = 112;
    static final int SURFACE_BYTES = 32;
    static final int SCRATCH_SIZE = 1024;
    static final int STATUS_BYTES = 96;
    static final int BAKE_HALO = FarAtlas.ALIGN;
    private static final int[] FORMATS = {GL30C.GL_RGBA16F, FarAtlas.LAYERED_NORMAL_FORMAT, GL30C.GL_RGBA16F,
            GL30C.GL_RG32F, GL30C.GL_RGBA32UI, GL30C.GL_R32UI};
    private final FarAtlas atlas = new FarAtlas(true);
    private final FarBuffer descriptors = new FarBuffer();
    private final FarBuffer rectangles = new FarBuffer();
    private final FarBuffer surfaces = new FarBuffer();
    private final FarRanges descriptorSlots = new FarRanges();
    private final FarRanges rectSlots = new FarRanges();
    private final FarRanges surfaceSlots = new FarRanges();
    private final List<Bake> active = new ArrayList<>();
    private final Map<Object, List<Tile>> cache = new HashMap<>();
    private final Map<Tile, Integer> references = new IdentityHashMap<>();
    private final FarReadback readback = new FarReadback();
    private final int vertexArray = GL45C.glCreateVertexArrays();

    static int headOffset(int level) {
        int offset = 0;
        for (int i = 0; i < level; i++) offset += (SCRATCH_SIZE >> i) * (SCRATCH_SIZE >> i);
        return offset;
    }

    // Each axis gets a whole padded pair on its own aligned shelf; 3 * (256 + 32) <= SCRATCH_SIZE.
    static int[] scratch(int[] extent) {
        int[] scratch = new int[3];
        int y = 0;
        for (int axis = 0; axis < 3; axis++) {
            scratch[axis] = y << 16;
            y += extent[(axis + 2) % 3] + 2 * BAKE_HALO;
        }
        return scratch;
    }

    boolean available() {
        return active.isEmpty();
    }

    Bake reserve(int sources) {
        long surfaceBytes = (long) sources * SURFACE_BYTES;
        if (surfaceBytes > GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE) {
            throw new IllegalStateException("Far layered bake source table exceeds the shader storage block limit");
        }
        var bake = new Bake(sources);
        active.add(bake);
        return bake;
    }

    void poll() {
        readback.poll();
    }

    void finish(Bake bake, FarPrograms programs, Consumer<Bake> completed) {
        bake.bind();
        var sort = programs.layerSort();
        sort.bind();
        sort.setBool("certifyQuads", true);
        sort.setUInt("sourceCount", bake.sourceCount);
        GL43C.glDispatchCompute((bake.sourceCount + 255) / 256, 1, 1);
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        sort.setBool("certifyQuads", false);
        sort.setUInt("pixelCount", SCRATCH_SIZE * SCRATCH_SIZE);
        sort.setBool("sourceOrder", true);
        sort.setBool("countLayers", true);
        GL43C.glDispatchCompute(SCRATCH_SIZE * SCRATCH_SIZE / 256, 1, 1);
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        var mip = programs.layerMip();
        for (int level = 1; level < FarAtlas.LEVELS; level++) {
            int width = SCRATCH_SIZE >> level;
            mip.bind();
            mip.setUInt("level", level);
            mip.setUInt("capacity", bake.capacity);
            mip.setUInt("inputOffset", headOffset(level - 1));
            mip.setUInt("outputOffset", headOffset(level));
            mip.setUInt("width", width);
            mip.setUInt("pixelCount", width * width);
            GL43C.glDispatchCompute((width * width + 255) / 256, 1, 1);
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        }
        sort.bind();
        sort.setUInt("pixelCount", headOffset(FarAtlas.LEVELS - 1) + 4096);
        sort.setBool("sourceOrder", false);
        sort.setBool("countLayers", false);
        GL43C.glDispatchCompute((headOffset(FarAtlas.LEVELS - 1) + 4096 + 255) / 256, 1, 1);
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        readback.request(bake.status.handle(), 0, STATUS_BYTES, data -> {
            if (bake.closed) return;
            bake.overflow = data.getInt(4) != 0;
            if (!bake.overflow) {
                for (int level = 0; level < FarAtlas.LEVELS; level++) {
                    if (data.getInt((level + 1) * 16 + 4) != 0) break;
                    bake.validMips |= 1 << level;
                    bake.ranksByMip[level] = data.getInt((level + 1) * 16);
                    bake.ranks = Math.max(bake.ranks, bake.ranksByMip[level]);
                }
            }
            completed.accept(bake);
        });
    }

    void compare(Bake bake, Object key, int[] extent, int[] scratch, FarPrograms programs) {
        bake.comparisonRequested = true;
        var candidates = cache.getOrDefault(key, List.of()).stream()
                              .filter(tile -> tile.sourceCount == bake.sourceCount && tile.ranks == bake.ranks
                                      && tile.validMips == bake.validMips).limit(4).toList();
        if (candidates.isEmpty()) {
            bake.comparisonReady = true;
            return;
        }
        candidates.forEach(tile -> references.merge(tile, 1, Integer::sum));
        bake.compares.ensure(candidates.size() * 4L);
        bake.compares.write(0, new int[candidates.size()], candidates.size());
        var program = programs.layerCompare();
        bind(program);
        bake.heads.bindStorage(13);
        bake.nodes.bindStorage(14);
        bake.source.bindStorage(9);
        bake.compares.bindStorage(11);
        program.bind();
        GL30C.glUniform3ui(program.getUniformLocation("extent"), extent[0], extent[1], extent[2]);
        GL30C.glUniform3ui(program.getUniformLocation("scratch"), scratch[0], scratch[1], scratch[2]);
        for (int i = 0; i < candidates.size(); i++) {
            program.setUInt("candidate", candidates.get(i).slot);
            program.setUInt("resultIndex", i);
            GL43C.glDispatchCompute(FarAtlas.LEVELS * 6, 1, 1);
        }
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        readback.request(bake.compares.handle(), 0, candidates.size() * 4, data -> {
            if (!bake.closed) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (data.getInt(i * 4) == 0) {
                        bake.match = candidates.get(i);
                        references.merge(bake.match, 1, Integer::sum);
                        break;
                    }
                }
                bake.comparisonReady = true;
            }
            candidates.forEach(this::release);
        });
    }

    @Nullable Tile publish(Bake bake, Object key, int[] extent, float[] clipExtent,
                           int[] scratch, FarPrograms programs) {
        if (bake.match != null) {
            var match = bake.match;
            bake.match = null;
            release(bake);
            return match;
        }
        var allocated = new ArrayList<FarAtlas.Rect>();
        int rectCount = FarAtlas.LEVELS;
        for (int level = 0; level < FarAtlas.LEVELS; level++) {
            rectCount += bake.ranksByMip[level];
            for (int rank = 0; rank < bake.ranksByMip[level]; rank++) {
                for (int axis = 0; axis < 3; axis++) {
                    var rect = atlas.allocate(2 * ((extent[(axis + 1) % 3] >> level) + 2),
                            (extent[(axis + 2) % 3] >> level) + 2);
                    if (rect == null) {
                        allocated.forEach(atlas::free);
                        return null;
                    }
                    allocated.add(rect);
                }
            }
        }
        int slot = descriptorSlots.allocate(1);
        int rectBase = rectSlots.allocate(rectCount);
        int surfaceBase = surfaceSlots.allocate(Math.max(bake.sourceCount, 1));
        descriptors.ensure((long) descriptorSlots.size() * 32, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE);
        rectangles.ensure((long) rectSlots.size() * 16, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE);
        surfaces.ensure((long) surfaceSlots.size() * SURFACE_BYTES, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE);
        int[] words = new int[rectCount * 4];
        int into = FarAtlas.LEVELS;
        int allocatedAt = 0;
        for (int level = 0; level < FarAtlas.LEVELS; level++) {
            words[level * 4] = rectBase + into;
            words[level * 4 + 1] = bake.ranksByMip[level];
            words[level * 4 + 2] = bake.validMips & 1 << level;
            for (int rank = 0; rank < bake.ranksByMip[level]; rank++, into++) {
                for (int axis = 0; axis < 3; axis++) words[into * 4 + axis] = allocated.get(allocatedAt++).packed();
                words[into * 4 + 3] = 1 << level;
            }
        }
        rectangles.write((long) rectBase * 16, words, words.length);
        GL45C.glCopyNamedBufferSubData(bake.source.handle(), surfaces.handle(), 0,
                (long) surfaceBase * SURFACE_BYTES, (long) bake.sourceCount * SURFACE_BYTES);
        bake.bind();
        rectangles.bindStorage(11);
        var copy = programs.layerCopy();
        copy.bind();
        GL30C.glUniform3ui(copy.getUniformLocation("extent"), extent[0], extent[1], extent[2]);
        GL30C.glUniform3ui(copy.getUniformLocation("scratch"), scratch[0], scratch[1], scratch[2]);
        for (int level = 0; bake.ranks > 0 && level < FarAtlas.LEVELS && (bake.validMips & 1 << level) != 0; level++) {
            copy.setUInt("level", level);
            copy.setUInt("inputOffset", headOffset(level));
            copy.setUInt("width", SCRATCH_SIZE >> level);
            copy.setUInt("ranks", bake.ranksByMip[level]);
            copy.setUInt("rectBase", words[level * 4]);
            bindImages();
            GL43C.glDispatchCompute(6, 1, 1);
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        GL42C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        descriptors.write((long) slot * 32, new int[]{rectBase, bake.ranks, bake.validMips,
                Float.floatToRawIntBits(clipExtent[2]), surfaceBase, bake.sourceCount,
                Float.floatToRawIntBits(clipExtent[0]), Float.floatToRawIntBits(clipExtent[1])}, 8);
        var tile = new Tile(slot, rectBase, surfaceBase, bake.ranks, bake.sourceCount, bake.validMips,
                List.copyOf(allocated), key, rectCount);
        cache.computeIfAbsent(key, _ -> new ArrayList<>()).add(tile);
        references.put(tile, 1);
        release(bake);
        return tile;
    }

    private void bindImages() {
        int[] textures = {atlas.color(), atlas.normal(), atlas.light(), atlas.aux(), atlas.material(), atlas.surface()};
        for (int i = 0; i < textures.length; i++) {
            GL42C.glBindImageTexture(i, textures[i], 0, true, 0, GL15C.GL_WRITE_ONLY, FORMATS[i]);
        }
    }

    void bind(GlProgram _) {
        descriptors.bindStorage(0);
        rectangles.bindStorage(3);
        surfaces.bindStorage(7);
        int[] textures = {atlas.color(), atlas.normal(), atlas.light(), atlas.aux(), atlas.material(), atlas.surface()};
        for (int i = 0; i < textures.length; i++) {
            int unit = 10 + i;
            if (unit < 12) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, textures[i]);
            } else GL45C.glBindTextureUnit(unit, textures[i]);
            GL33C.glBindSampler(unit, 0);
        }
    }

    void bindVertexArray() {
        GlStateManager._glBindVertexArray(vertexArray);
    }

    void release(Bake bake) {
        if (bake.match != null) {
            release(bake.match);
            bake.match = null;
        }
        active.remove(bake);
        bake.close();
    }

    void release(Tile tile) {
        int refs = references.get(tile) - 1;
        if (refs != 0) {
            references.put(tile, refs);
            return;
        }
        references.remove(tile);
        var known = cache.get(tile.key);
        known.remove(tile);
        if (known.isEmpty()) cache.remove(tile.key);
        tile.rects.forEach(atlas::free);
        descriptorSlots.free(tile.slot, 1);
        rectSlots.free(tile.rectBase, tile.rectCount);
        surfaceSlots.free(tile.surfaceBase, Math.max(tile.sourceCount, 1));
    }

    @Override
    public void close() {
        readback.delete();
        active.forEach(Bake::close);
        active.clear();
        cache.clear();
        references.clear();
        descriptors.delete();
        rectangles.delete();
        surfaces.delete();
        atlas.delete();
        GL30C.glDeleteVertexArrays(vertexArray);
    }

    static final class Bake implements AutoCloseable {
        final int sourceCount;
        final int capacity = (int) Math.min(1024L * 1024, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE / NODE_BYTES);
        final FarBuffer heads = new FarBuffer();
        final FarBuffer nodes = new FarBuffer();
        final FarBuffer status = new FarBuffer();
        final FarBuffer source = new FarBuffer();
        // Two vec4 slots per primitive: the four actual transformed corners of a canonical two-triangle quad.
        final FarBuffer corners = new FarBuffer();
        final FarBuffer indices = new FarBuffer();
        final FarBuffer jobs = new FarBuffer();
        final FarBuffer commands = new FarBuffer();
        final FarBuffer drawMeta = new FarBuffer();
        final FarBuffer compares = new FarBuffer();
        boolean overflow;
        boolean closed;
        int ranks;
        int validMips;
        final int[] ranksByMip = new int[FarAtlas.LEVELS];
        boolean comparisonRequested;
        boolean comparisonReady;
        @Nullable Tile match;

        private Bake(int sourceCount) {
            this.sourceCount = sourceCount;
            heads.ensure((long) (headOffset(FarAtlas.LEVELS - 1) + 4096) * 4);
            nodes.ensure((long) capacity * NODE_BYTES, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE);
            status.ensure(STATUS_BYTES);
            source.ensure(Math.max((long) sourceCount * SURFACE_BYTES, 32), GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE);
            corners.ensure(Math.max((long) sourceCount * 32, 32), GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE);
            GL45C.glClearNamedBufferData(corners.handle(), GL30C.GL_R32UI, GL30C.GL_RED_INTEGER,
                    GL11C.GL_UNSIGNED_INT, new int[]{0});
            GL45C.glClearNamedBufferData(heads.handle(), GL30C.GL_R32UI, GL30C.GL_RED_INTEGER,
                    GL11C.GL_UNSIGNED_INT, new int[]{-1});
            GL45C.glClearNamedBufferData(status.handle(), GL30C.GL_R32UI, GL30C.GL_RED_INTEGER,
                    GL11C.GL_UNSIGNED_INT, new int[]{0});
            GL45C.glClearNamedBufferData(source.handle(), GL30C.GL_R32UI, GL30C.GL_RED_INTEGER,
                    GL11C.GL_UNSIGNED_INT, new int[]{0});
        }

        void bind() {
            heads.bindStorage(0);
            nodes.bindStorage(3);
            status.bindStorage(7);
            source.bindStorage(9);
            corners.bindStorage(14);
        }

        @Override
        public void close() {
            closed = true;
            for (var buffer : List.of(heads, nodes, status, source, corners, indices, jobs, commands, drawMeta,
                    compares))
                buffer.delete();
        }
    }

    record Tile(int slot, int rectBase, int surfaceBase, int ranks, int sourceCount, int validMips,
                List<FarAtlas.Rect> rects, Object key, int rectCount) {
    }
}
