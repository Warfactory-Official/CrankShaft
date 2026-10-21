package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.engine.AbstractArena;
import dev.engine_room.flywheel.backend.engine.IndexPool;
import dev.engine_room.flywheel.backend.engine.indirect.ResizableStorageBuffer;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.QuadIndexSequence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;

final class GuestTerrainBatch {
    private static final int COMMAND_BYTES = 40;
    private final Int2ObjectOpenHashMap<Bucket> buckets = new Int2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Object, Allocation> allocations = new Reference2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<GuestPrograms.Program> programs = new Int2ObjectOpenHashMap<>();
    private final List<Run> ordered = new ArrayList<>();
    private int orderedCount;
    private final IndexPool indices = new IndexPool();
    private final GlVertexArray vao = GlVertexArray.create();
    private IndexPool patchIndices;
    private GuestHost owner;
    private long uploadedBytes;
    private long uploadedCommandBytes;
    private long submittedBatches;
    private long nativeEpoch = -1;
    private int commandSet;
    private int originX, originY, originZ;
    private boolean swept;

    GuestTerrainBatch() {
        indices.bind(vao);
    }

    void prepare(List<RenderChunk> chunks, BlockRenderLayer layer, boolean patches) {
        prepare(chunks, layer, false, patches);
    }

    private void prepare(List<RenderChunk> chunks, BlockRenderLayer layer, boolean preserveOrder, boolean patches) {
        begin(layer, preserveOrder, patches);
        var host = GuestHost.current();
        var maximumIndices = 0;
        for (var chunk : chunks) {
            var vbo = chunk.getVertexBufferByLayer(layer.ordinal());
            if (vbo.count == 0) continue;
            var source = host.terrainSlice(vbo);
            var pos = chunk.getPosition();
            var region = source.region();
            maximumIndices = Math.max(maximumIndices, add(vbo, ((TerrainBuffer) vbo).flywheel$uploadVersion(),
                    source.buffer(), source.offset(), vbo.count, vbo.vertexFormat.getSize(),
                    TerrainBuffer.originCoordinate(pos.getX(), region), TerrainBuffer.originCoordinate(pos.getY(), region),
                    TerrainBuffer.originCoordinate(pos.getZ(), region), region, preserveOrder, patches));
        }
        finish(maximumIndices, patches);
    }

    void prepareNative(NothiriumTerrain.Scope chunks, boolean preserveOrder, boolean patches) {
        if (nativeEpoch != NothiriumTerrain.bufferEpoch()) {
            for (var iterator = Reference2ObjectMaps.fastIterator(allocations); iterator.hasNext();) {
                var entry = iterator.next();
                if (!(entry.getKey() instanceof NativeTerrainBuffer)) continue;
                entry.getValue().bucket.free(entry.getValue().slot);
                iterator.remove();
            }
            nativeEpoch = NothiriumTerrain.bufferEpoch();
        }
        begin(BlockRenderLayer.TRANSLUCENT, preserveOrder, patches);
        var maximumIndices = 0;
        for (var index = 0; index < chunks.size(); index++) {
            // Nothirium allocates immutable parts for rebuilds and translucent re-sorts; pool growth changes the buffer ID.
            maximumIndices = Math.max(maximumIndices, add(chunks.owner(index), 0, chunks.buffer(index), chunks.offset(index),
                    chunks.count(index), chunks.stride(index), chunks.x(index), chunks.y(index), chunks.z(index), true, preserveOrder, patches));
        }
        finish(maximumIndices, patches);
    }

    private void begin(BlockRenderLayer layer, boolean preserveOrder, boolean patches) {
        orderedCount = 0;
        commandSet = layer.ordinal() * 4 + (GuestHost.current().shadow() ? 2 : 0)
                + (preserveOrder ? 1 : 0) + (patches ? 16 : 0);
        for (var bucket : buckets.values()) bucket.beginCommands(commandSet);
        var origin = VisualizationManager.get(Minecraft.getMinecraft().world).renderOrigin();
        originX = origin.getX();
        originY = origin.getY();
        originZ = origin.getZ();
        swept = false;
    }

    private int add(Object owner, long revision, int sourceBuffer, long sourceOffset, int count, int stride,
                    int x, int y, int z, boolean region, boolean preserveOrder, boolean patches) {
        if (stride != 28 && stride != 56) throw new IllegalStateException("Unsupported terrain vertex stride " + stride);
        var bytes = Math.multiplyExact(count, stride);
        var allocation = allocations.get(owner);
        if (allocation == null && !swept) {
            for (var iterator = Reference2ObjectMaps.fastIterator(allocations); iterator.hasNext();) {
                var entry = iterator.next();
                var key = entry.getKey();
                var live = key instanceof VertexBuffer vbo ? GuestHost.current().terrainSlice(vbo).buffer() >= 0
                        : ((NativeTerrainBuffer) key).flywheel$isValid();
                if (live) continue;
                entry.getValue().bucket.free(entry.getValue().slot);
                iterator.remove();
            }
            swept = true;
        }
        if (allocation == null || allocation.bucket.elementBytes < bytes) {
            if (allocation != null) allocation.bucket.free(allocation.slot);
            var capacity = bytes <= 256 ? 256 : Math.multiplyExact(Integer.highestOneBit(bytes - 1), 2);
            var bucket = buckets.computeIfAbsent(capacity, bytesPerElement -> new Bucket(bytesPerElement, commandSet));
            allocation = new Allocation(bucket, bucket.alloc());
            allocations.put(owner, allocation);
        }
        if (allocation.revision != revision || allocation.sourceBuffer != sourceBuffer || allocation.sourceOffset != sourceOffset) {
            // TODO (NV_shader_buffer_load): Address native VBOs directly once rebuild/delete address retirement is fenced.
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL45.glCopyNamedBufferSubData(sourceBuffer, allocation.bucket.vertices.handle(), sourceOffset,
                    allocation.bucket.byteOffsetOf(allocation.slot), bytes);
            allocation.revision = revision;
            allocation.sourceBuffer = sourceBuffer;
            allocation.sourceOffset = sourceOffset;
            uploadedBytes += bytes;
        }
        var bucket = allocation.bucket;
        if (preserveOrder) {
            if (orderedCount != 0 && ordered.get(orderedCount - 1).bucket == bucket) ordered.get(orderedCount - 1).end++;
            else {
                if (orderedCount == ordered.size()) ordered.add(new Run());
                var run = ordered.get(orderedCount++);
                run.bucket = bucket;
                run.start = bucket.count;
                run.end = bucket.count + 1;
            }
        }
        bucket.ensureCommands((long) (bucket.count + 1) * COMMAND_BYTES);
        var indexCount = patches ? count : count / 4 * 6;
        bucket.command(indexCount, Math.toIntExact(bucket.byteOffsetOf(allocation.slot) / 4),
                x - originX, y - originY, z - originZ, stride / 4 | (region ? 0x80000000 : 0));
        bucket.count++;
        return indexCount;
    }

    private void finish(int maximumIndices, boolean patches) {
        if (patches && patchIndices == null) patchIndices = new IndexPool();
        var indexPool = patches ? patchIndices : indices;
        indexPool.updateCount(patches ? GuestPatchIndices.QUADS : QuadIndexSequence.INSTANCE, maximumIndices);
        indexPool.flush();
        for (var bucket : buckets.values()) {
            bucket.current.count = bucket.count;
            if (bucket.count == 0 || !bucket.commandsChanged) continue;
            var bytes = (long) bucket.count * COMMAND_BYTES;
            bucket.draws.upload(bucket.commands.ptr(), bytes);
            uploadedCommandBytes += bytes;
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
    }

    void draw(GuestPrograms.Program program) {
        draw(program, false);
    }

    private void draw(GuestPrograms.Program program, boolean preserveOrder) {
        // Legacy native parts have no material attributes; retain AUSM's per-layer fallback.
        NothiriumTerrain.bindFallback(program.program);
        (program.tessellated ? patchIndices : indices).bind(vao);
        vao.bindForDraw();
        if (preserveOrder) {
            for (var index = 0; index < orderedCount; index++) {
                var run = ordered.get(index);
                draw(program, run.bucket, run.start, run.end);
            }
            return;
        }
        for (var bucket : buckets.values()) {
            if (bucket.count == 0) continue;
            draw(program, bucket, 0, bucket.count);
        }
    }

    private void draw(GuestPrograms.Program program, Bucket bucket, int start, int end) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bucket.vertices.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, bucket.draws.handle());
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, bucket.draws.handle());
        GlCompat.safeMultiDrawElementsIndirect(program.program, program.primitive(), GL11.GL_UNSIGNED_INT, start, end,
                COMMAND_BYTES);
        submittedBatches++;
    }

    void drawNative(GuestRenderPass pass, List<RenderChunk> chunks, BlockRenderLayer layer) {
        if (owner != pass.host) {
            programs.values().forEach(program -> program.program.delete());
            programs.clear();
            owner = pass.host;
        }
        var preserveOrder = layer == BlockRenderLayer.TRANSLUCENT;
        var program = programs.get(pass.program);
        if (program == null) {
            program = GuestPrograms.terrain(pass, null, true);
            programs.put(pass.program, program);
        }
        if (NothiriumTerrain.captured()) prepareNative(NothiriumTerrain.slices(), preserveOrder, program.tessellated);
        else prepare(chunks, layer, preserveOrder, program.tessellated);
        program.bind(pass, 0);
        draw(program, preserveOrder);
    }

    long submittedBatches() {
        return submittedBatches;
    }

    long uploadedBytes() {
        return uploadedBytes;
    }

    long uploadedCommandBytes() {
        return uploadedCommandBytes;
    }

    void delete() {
        programs.values().forEach(program -> program.program.delete());
        programs.clear();
        buckets.values().forEach(Bucket::delete);
        buckets.clear();
        allocations.clear();
        ordered.clear();
        orderedCount = 0;
        vao.delete();
        indices.delete();
        if (patchIndices != null) patchIndices.delete();
    }

    private static final class Allocation {
        private final Bucket bucket;
        private final int slot;
        private long revision = -1;
        private int sourceBuffer = -1;
        private long sourceOffset;

        Allocation(Bucket bucket, int slot) {
            this.bucket = bucket;
            this.slot = slot;
        }
    }

    private static final class Run {
        private Bucket bucket;
        private int start, end;
    }

    private static final class Bucket extends AbstractArena {
        private final int elementBytes;
        private final ResizableStorageBuffer vertices = new ResizableStorageBuffer();
        private final Commands[] commandSets = new Commands[32];
        private Commands current;
        private GlBuffer draws;
        private MemoryBlock commands;
        private int count;
        private int previousCount;
        private boolean commandsChanged;

        Bucket(int elementBytes, int commandSet) {
            super(elementBytes);
            this.elementBytes = elementBytes;
            vertices.ensureCapacity((long) elementBytes * 4);
            beginCommands(commandSet);
        }

        @Override
        public long byteCapacity() {
            return vertices.capacity();
        }

        @Override
        protected void grow() {
            vertices.ensureCapacity(vertices.capacity() * 2);
        }

        void ensureCommands(long size) {
            if (commands.size() < size) current.data = commands = commands.realloc(Math.max(commands.size() * 2, size));
        }

        void beginCommands(int key) {
            if (commandSets[key] == null) commandSets[key] = new Commands();
            current = commandSets[key];
            commands = current.data;
            draws = current.buffer;
            previousCount = current.count;
            count = 0;
            commandsChanged = false;
        }

        void command(int indices, int wordOffset, float x, float y, float z, int stride) {
            var ptr = commands.ptr() + (long) count * COMMAND_BYTES;
            if (count < previousCount && MemoryUtil.memGetInt(ptr) == indices
                    && MemoryUtil.memGetInt(ptr + 20) == wordOffset
                    && MemoryUtil.memGetFloat(ptr + 24) == x && MemoryUtil.memGetFloat(ptr + 28) == y
                    && MemoryUtil.memGetFloat(ptr + 32) == z && MemoryUtil.memGetInt(ptr + 36) == stride) return;
            // Camera motion leaves render-origin-relative commands intact; visibility/order and origin changes do not.
            commandsChanged = true;
            MemoryUtil.memPutInt(ptr, indices);
            MemoryUtil.memPutInt(ptr + 4, 1);
            MemoryUtil.memPutLong(ptr + 8, 0);
            MemoryUtil.memPutInt(ptr + 16, 0);
            MemoryUtil.memPutInt(ptr + 20, wordOffset);
            MemoryUtil.memPutFloat(ptr + 24, x);
            MemoryUtil.memPutFloat(ptr + 28, y);
            MemoryUtil.memPutFloat(ptr + 32, z);
            MemoryUtil.memPutInt(ptr + 36, stride);
        }

        void delete() {
            vertices.delete();
            for (var set : commandSets)
                if (set != null) {
                    set.buffer.delete();
                    set.data.free();
                }
        }
    }

    private static final class Commands {
        private final GlBuffer buffer = new GlBuffer(GlBufferUsage.STREAM_DRAW);
        private MemoryBlock data = MemoryBlock.malloc(COMMAND_BYTES * 16);
        private int count;
    }
}
