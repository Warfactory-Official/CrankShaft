package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.engine_room.flywheel.backend.engine.BufferRetirement;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Receiver IDs follow vertex allocations, including arena moves; geometry and index bytes are unchanged.
 */
public final class TerrainReceiverBuffers {
    private static final Map<GpuBuffer, Storage> BUFFERS = new IdentityHashMap<>();
    private static GpuBuffer zero;
    private static long zeroView;

    private TerrainReceiverBuffers() {
    }

    public static void prepare() {
        if (zero != null) return;
        var bytes = MemoryUtil.memCalloc(4);
        try {
            zero = RenderSystem.getDevice().createBuffer(() -> "Empty terrain receivers",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        zeroView = texelView(zero);
    }

    public static GpuBufferSlice get(GpuBuffer vertices) {
        var storage = BUFFERS.get(vertices);
        return (storage == null ? zero : storage.buffer).slice();
    }

    #if MC < 26.3
    /**
     * Same-vertex-buffer runs of {@code draws}, in order: 26.2 GL uploads a pass's uniforms once per multi-draw and binds each draw's vertex buffer itself.
     */
    public static <T> void forEachRun(List<RenderPass.Draw<T>> draws,
                                      BiConsumer<GpuBufferSlice, List<RenderPass.Draw<T>>> run) {
        for (int start = 0, end; start < draws.size(); start = end) {
            var vertices = draws.get(start).vertexBuffer();
            for (end = start + 1; end < draws.size() && draws.get(end).vertexBuffer() == vertices; end++) ;
            run.accept(get(vertices), draws.subList(start, end));
        }
    }
    #endif

    public static GpuBuffer find(GpuBuffer vertices) {
        var storage = BUFFERS.get(vertices);
        return storage == null ? zero : storage.buffer;
    }

    public static void bind(VkDescriptorWriter writer, int binding, GpuBuffer vertices) {
        var storage = BUFFERS.get(vertices);
        if (storage == null) writer.uintTexel(binding, zero.slice(), zeroView);
        else writer.uintTexel(binding, storage.buffer.slice(), storage.view);
    }

    public static void upload(GpuBuffer vertices, int stride, long firstVertex, int count, int[] ids) {
        var storage = BUFFERS.get(vertices);
        if (storage == null) {
            if (ids.length == 0) return;
            storage = new Storage(stride);
            BUFFERS.put(vertices, storage);
        }
        if (storage.stride != stride) throw new IllegalStateException("Terrain vertex stride changed within a buffer");
        int first = Math.toIntExact(firstVertex), end = Math.addExact(first, count);
        if (ids.length != 0 && ids.length != count)
            throw new IllegalArgumentException("Receiver count differs from vertex count");
        if (ids.length == 0 && first >= storage.ids.length) return;
        int required = ids.length == 0 ? Math.min(end, storage.ids.length) : end;
        if (ids.length == 0) {
            boolean dirty = false;
            for (int at = first; at < required; at++)
                if (storage.ids[at] != 0) {
                    dirty = true;
                    break;
                }
            if (!dirty) return;
        }
        boolean resized = storage.ensure(required);
        if (ids.length == 0) Arrays.fill(storage.ids, first, required, 0);
        else System.arraycopy(ids, 0, storage.ids, first, count);
        storage.write(resized ? 0 : first, resized ? storage.ids.length : required);
    }

    public static void copy(GpuBufferSlice source, GpuBufferSlice target) {
        var from = BUFFERS.get(source.buffer());
        var into = BUFFERS.get(target.buffer());
        if (from == null && into == null) return;
        if (from == null && (source.buffer().usage() & GpuBuffer.USAGE_VERTEX) == 0) return;
        int stride = from == null ? into.stride : from.stride;
        if (source.offset() % stride != 0 || target.offset() % stride != 0 || source.length() % stride != 0)
            throw new IllegalStateException("A terrain arena move split a vertex");
        int first = Math.toIntExact(source.offset() / stride), count = Math.toIntExact(source.length() / stride);
        int[] ids = TerrainReceiverCompilation.EMPTY;
        if (from != null && first < from.ids.length) {
            ids = new int[count];
            System.arraycopy(from.ids, first, ids, 0, Math.min(count, from.ids.length - first));
        }
        upload(target.buffer(), stride, target.offset() / stride, count, ids);
    }

    public static void release(GpuBuffer vertices) {
        var storage = BUFFERS.remove(vertices);
        if (storage != null && storage.buffer != null) retire(storage.buffer, storage.view);
    }

    public static void close() {
        for (var storage : BUFFERS.values()) if (storage.buffer != null) retire(storage.buffer, storage.view);
        BUFFERS.clear();
        if (zero != null) {
            retire(zero, zeroView);
            zero = null;
            zeroView = 0L;
        }
    }

    private static long texelView(GpuBuffer buffer) {
        if (!VkContext.isVulkanHost()) return 0L;
        try (var stack = MemoryStack.stackPush()) {
            var info = VkBufferViewCreateInfo.calloc(stack).sType$Default().buffer(VkContext.buffer(buffer))
                                             .format(VK10.VK_FORMAT_R32_UINT).offset(0L).range(buffer.size());
            var view = stack.mallocLong(1);
            VkCaps.checkPipelineResult(VK10.vkCreateBufferView(VkContext.vkDevice(), info, null, view),
                    "uint texel buffer view");
            return view.get(0);
        }
    }

    private static void retire(GpuBuffer buffer, long view) {
        if (view != 0L) VkContext.deferDestroy(() -> VK10.vkDestroyBufferView(VkContext.vkDevice(), view, null));
        BufferRetirement.retire(buffer);
    }

    private static final class Storage {
        private final int stride;
        private int[] ids = TerrainReceiverCompilation.EMPTY;
        private GpuBuffer buffer;
        private long view;

        private Storage(int stride) {
            this.stride = stride;
        }

        private boolean ensure(int required) {
            if (required <= ids.length) return false;
            int capacity = Math.max(required, Math.max(256, ids.length * 2));
            ids = Arrays.copyOf(ids, capacity);
            if (buffer != null) retire(buffer, view);
            buffer = RenderSystem.getDevice().createBuffer(() -> "Terrain receivers",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC,
                    capacity * 4L + 4);
            view = texelView(buffer);
            var header = MemoryUtil.memAlloc(4).order(ByteOrder.nativeOrder());
            try {
                header.putInt(0, capacity);
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, 4), header);
            } finally {
                MemoryUtil.memFree(header);
            }
            return true;
        }

        private void write(int first, int end) {
            var bytes = MemoryUtil.memAlloc((end - first) * 4).order(ByteOrder.nativeOrder());
            try {
                bytes.asIntBuffer().put(ids, first, end - first);
                RenderSystem.getDevice().createCommandEncoder()
                            .writeToBuffer(buffer.slice(first * 4L + 4, bytes.remaining()), bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
        }
    }
}
