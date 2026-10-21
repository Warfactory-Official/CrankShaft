package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.indirect.*;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.*;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;

final class GuestIndirectBatches {
    private final SharedIndirectStorage storage;
    private final GlBuffer sources = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
    private final GlBuffer typeBases = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
    private final ResizableStorageArray commands = new ResizableStorageArray(IndirectBuffers.DRAW_COMMAND_STRIDE);
    private ResizableStorageArray patchCommands;
    private long patchVersion = -1;
    private final Reference2ObjectOpenHashMap<IndirectCullingGroup<?>, Layout> layouts = new Reference2ObjectOpenHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Batch> batches = new ArrayList<>();
    private MemoryBlock types = MemoryBlock.calloc(16, 1);
    private GuestHost owner;
    private int frame;
    private final int maxGroups = GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0);
    private final GlProgram assemble = GuestPrograms.compute("#version 430 core\n"
            + GuestContractShaders.library(ResourceUtil.rl("internal/indirect/draw_command.glsl")) + """
            layout(local_size_x=64) in;
            layout(std430,binding=0) readonly buffer Sources { uint sources[]; };
            layout(std430,binding=1) readonly buffer Input { MeshDrawCommand inputDraws[]; };
            layout(std430,binding=2) writeonly buffer Output { MeshDrawCommand outputDraws[]; };
            layout(std430,binding=3) writeonly buffer PatchOutput { MeshDrawCommand patchDraws[]; };
            uniform uint first;
            uniform uint count;
            uniform uint sourceStride;
            uniform bool buildPatches;
            void main() {
                uint index=first+gl_GlobalInvocationID.x;
                if(index>=count) return;
                uint source=index*sourceStride;
                MeshDrawCommand draw=inputDraws[sources[source]];
                outputDraws[index]=draw;
                if(buildPatches) {
                    draw.indexCount=sources[source+1u];
                    draw.firstIndex=sources[source+2u];
                    patchDraws[index]=draw;
                }
            }
            """);

    GuestIndirectBatches(SharedIndirectStorage storage) {
        this.storage = storage;
    }

    static boolean compatible(GuestIndirectDraw first, GuestIndirectDraw next, GuestHost host) {
        return first.isEmbedded() == next.isEmbedded() && first.tag(host) == next.tag(host) && first.itemTag(
                host) == next.itemTag(host)
                && first.metadata().borrowedRenderType == next.metadata().borrowedRenderType
                && first.metadata().borrowedMetadata == next.metadata().borrowedMetadata
                && first.metadata().borrowedEmission == next.metadata().borrowedEmission
                && Objects.equals(first.entityKey(), next.entityKey()) && first.castsShadow(host) == next.castsShadow(
                host)
                && MaterialEncoder.packProperties(first.material()) == MaterialEncoder.packProperties(next.material())
                && first.material().fog().source().equals(next.material().fog().source())
                && MaterialRenderState.materialEquals(first.material(), next.material());
    }

    void prepare(GuestHost host, Collection<? extends IndirectCullingGroup<?>> groups, GuestPatchIndices patches) {
        var version = patches == null ? -1 : patches.version();
        var rebuild = owner != host || layouts.size() != groups.size() || patchVersion != version;
        patchVersion = version;
        var typeBytes = Math.max(16, InstanceTypeIds.count() * 16L);
        var changedTypes = types.size() < typeBytes;
        if (changedTypes) types = types.realloc(typeBytes);
        frame++;
        for (var group : groups) {
            var layout = layouts.computeIfAbsent(group, _ -> new Layout());
            layout.frame = frame;
            var buffers = group.buffers();
            var drawOffset = buffers.draw.byteOffset();
            if (layout.version != group.drawVersion() || layout.drawOffset != drawOffset) rebuild = true;
            layout.version = group.drawVersion();
            layout.drawOffset = drawOffset;
            var draw = (GuestIndirectDraw) group.draws().getFirst();
            var type = draw.groupKey().instanceType();
            var ptr = types.ptr() + InstanceTypeIds.id(type) * 16L;
            var objectBase = Math.toIntExact(buffers.objectStorage.byteOffset() / 4);
            var indexBase = Math.toIntExact(buffers.drawInstanceIndex.byteOffset() / 4);
            var stride = MoreMath.align4(type.layout().byteSize()) / 4;
            if (MemoryUtil.memGetInt(ptr) != objectBase || MemoryUtil.memGetInt(ptr + 4) != indexBase
                    || MemoryUtil.memGetInt(ptr + 8) != stride) {
                MemoryUtil.memPutInt(ptr, objectBase);
                MemoryUtil.memPutInt(ptr + 4, indexBase);
                MemoryUtil.memPutInt(ptr + 8, stride);
                MemoryUtil.memPutInt(ptr + 12, 0);
                changedTypes = true;
            }
        }
        for (var iterator = Reference2ObjectMaps.fastIterator(layouts); iterator.hasNext();) {
            if (iterator.next().getValue().frame == frame) continue;
            iterator.remove();
            rebuild = true;
        }
        if (changedTypes || owner != host) typeBases.upload(types.ptr(), typeBytes);
        if (rebuild) rebuild(host, groups, patches);
        owner = host;
        assemble.bind();
        assemble.setUInt("count", entries.size());
        assemble.setUInt("sourceStride", patches == null ? 1 : 3);
        assemble.setInt("buildPatches", patches == null ? 0 : 1);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, sources.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, storage.draws.buffer.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, commands.handle());
        if (patches != null) {
            if (patchCommands == null) patchCommands = new ResizableStorageArray(IndirectBuffers.DRAW_COMMAND_STRIDE);
            patchCommands.ensureCapacity(Math.max(1, entries.size()));
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, patchCommands.handle());
        }
        for (var first = 0; first < entries.size(); ) {
            var groupsThisDispatch = Math.min(maxGroups, MoreMath.ceilingDiv(entries.size() - first, 64));
            assemble.setUInt("first", first);
            GL43.glDispatchCompute(groupsThisDispatch, 1, 1);
            first += groupsThisDispatch * 64;
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
    }

    private void rebuild(GuestHost host, Collection<? extends IndirectCullingGroup<?>> groups,
                         GuestPatchIndices patches) {
        entries.clear();
        batches.clear();
        for (var group : groups) {
            var first = Math.toIntExact(group.buffers().draw.byteOffset() / IndirectBuffers.DRAW_COMMAND_STRIDE);
            for (var index = 0; index < group.draws().size(); index++) {
                entries.add(new Entry((GuestIndirectDraw) group.draws().get(index), first + index));
            }
        }
        entries.sort(Comparator.comparingInt((Entry entry) -> entry.draw.bias())
                               .thenComparingInt(entry -> entry.draw.indexOfMeshInModel())
                               .thenComparing(entry -> entry.draw.material(), MaterialRenderState.COMPARATOR)
                               .thenComparingInt(entry -> entry.draw.tag(host))
                               .thenComparingInt(entry -> entry.draw.itemTag(host))
                               .thenComparingInt(Entry::source));
        var sourceStride = patches == null ? 4 : 12;
        var ids = MemoryBlock.malloc(Math.max(4, entries.size() * (long) sourceStride));
        try {
            for (var index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                var ptr = ids.ptr() + index * (long) sourceStride;
                MemoryUtil.memPutInt(ptr, entry.source);
                if (patches != null) {
                    MemoryUtil.memPutInt(ptr + 4, GuestPatchIndices.count(entry.draw.mesh()));
                    MemoryUtil.memPutInt(ptr + 8, patches.first(entry.draw.mesh()));
                }
            }
            sources.upload(ids.ptr(), Math.max(4, entries.size() * (long) sourceStride));
        } finally {
            ids.free();
        }
        commands.ensureCapacity(Math.max(1, entries.size()));
        for (var start = 0; start < entries.size(); ) {
            var first = entries.get(start).draw;
            var end = start + 1;
            while (end < entries.size() && compatible(first, entries.get(end).draw, host)) end++;
            batches.add(new Batch(first, start, end));
            start = end;
        }
    }

    List<Batch> batches() {
        return batches;
    }

    void clear() {
        entries.clear();
        batches.clear();
        layouts.clear();
    }

    void bind(boolean patches) {
        var draws = patches ? patchCommands : commands;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, typeBases.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, storage.objects.buffer.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, storage.indices.buffer.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, draws.handle());
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, draws.handle());
    }

    void delete() {
        sources.delete();
        typeBases.delete();
        commands.delete();
        assemble.delete();
        types.free();
        layouts.clear();
        if (patchCommands != null) patchCommands.delete();
    }

    record Batch(GuestIndirectDraw draw, int start, int end) {
    }

    private record Entry(GuestIndirectDraw draw, int source) {
    }

    private static final class Layout {
        long version = -1, drawOffset = -1;
        int frame;
    }
}
