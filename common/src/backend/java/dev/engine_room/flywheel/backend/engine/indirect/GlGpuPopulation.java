package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.GpuSimulation;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

import static org.lwjgl.opengl.GL15C.nglGetBufferSubData;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.GL45C.glClearNamedBufferSubData;

/** Persistent simulation buffers, independent of the render origin and of the object's page addresses. */
final class GlGpuPopulation implements GpuPopulationResources {
    private static final Identifier SORT_CHECK = ResourceUtil.rl("internal/compute/sort_check.comp");
    private static final Identifier SORT_SMALL = ResourceUtil.rl("internal/compute/sort_small.comp");
    private static final Identifier SORT_COUNT = ResourceUtil.rl("internal/compute/sort_count.comp");
    private static final Identifier SORT_PREFIX = ResourceUtil.rl("internal/compute/sort_prefix.comp");
    private static final Identifier SORT_SCATTER = ResourceUtil.rl("internal/compute/sort_scatter.comp");
    private static final Identifier PUBLISH_PAGES = ResourceUtil.rl("internal/compute/publish_pages.comp");

    private final GpuIndirectInstancer<?> instancer;
    private final GpuSimulation simulation;
    private final IndirectPrograms programs;
    private final int capacity;
    private final MemoryBlock parameters;
    private final MemoryBlock auxiliary;
    private final IntArrayList auxiliaryRanges = new IntArrayList();
    private @Nullable Storage storage;
    private @Nullable SnapshotReader invalidateReader;
    private boolean parametersDirty;
    private boolean initialized;
    private boolean retired;
    private boolean closed;
    private int tick;
    private int targetTick;
    private int addressVersion = -1;

    GlGpuPopulation(GpuIndirectInstancer<?> instancer, IndirectPrograms programs) {
        this.instancer = instancer;
        this.simulation = instancer.simulation;
        this.capacity = instancer.capacity;
        this.programs = programs;
        parameters = MemoryBlock.calloc(simulation.parameterSize(), 1);
        auxiliary = MemoryBlock.calloc(simulation.auxiliarySize(), 1);
    }

    @Override
    public void initialize(int tick) {
        if (initialized) {
            throw new IllegalStateException("The GPU population has already been initialized");
        }
        this.tick = tick;
    }

    @Override
    public void parameters(int targetTick, LongConsumer writer) {
        writer.accept(parameters.ptr());
        this.targetTick = targetTick;
        parametersDirty = true;
    }

    @Override
    public void auxiliary(int offset, int bytes, LongConsumer writer) {
        writer.accept(auxiliary.ptr() + offset);
        auxiliaryRanges.add(offset);
        auxiliaryRanges.add(bytes);
    }

    @Override
    public void onInvalidate(SnapshotReader reader) {
        invalidateReader = reader;
    }

    @Override
    public boolean deleted() {
        return retired || closed;
    }

    @Override
    public void delete() {
        retired = true;
    }

    void recordHistory(ObjectHistory history) {
        int stride = instancer.strideUints();
        long pageBytes = (long) stride * 4 * 32;
        for (int page = 0; page < instancer.mapping.pageCount(); page++) {
            long offset = instancer.mapping.page2ByteOffset(page);
            history.record(offset, pageBytes);
            history.moved(offset, stride, -1);
        }
    }

    void prepare(ObjectStorage objects) {
        if (storage == null) {
            GlCompat.pushDebugGroup("flywheel:gpu/allocate");
            try {
                storage = new Storage();
            } finally {
                GlCompat.popDebugGroup();
            }
        }
        var buffers = storage;
        GlCompat.pushDebugGroup("flywheel:gpu/upload");
        try {
            if (parametersDirty) {
                buffers.parameters.uploadSpan(0, parameters);
                parametersDirty = false;
            }
            for (int range = 0; range < auxiliaryRanges.size(); range += 2) {
                int offset = auxiliaryRanges.getInt(range);
                int size = auxiliaryRanges.getInt(range + 1);
                buffers.auxiliary.uploadSpan(offset, auxiliary.ptr() + offset, size);
            }
            auxiliaryRanges.clear();
            if (addressVersion != instancer.mapping.slotVersion()) {
                uploadAddresses(buffers.addresses);
                addressVersion = instancer.mapping.slotVersion();
            }
        } finally {
            GlCompat.popDebugGroup();
        }

        bindSimulation(objects);
        if (!initialized) {
            var initialize = programs.getSimulationProgram(simulation.initializeShader(), simulation);
            dispatch(initialize, 0, tick, 1, "flywheel:gpu/initialize");
            initialized = true;
        }

        var update = programs.getSimulationProgram(simulation.tickShader(), simulation);
        int groups = (capacity + simulation.workGroupSize() - 1) / simulation.workGroupSize();
        int steps = Math.min(simulation.maxTicksPerFrame(), Math.max(0, targetTick - tick));
        GlCompat.pushDebugGroup("flywheel:gpu/ticks");
        try {
            for (int step = 0; step < steps; step++) {
                tick++;
                dispatch(update, 0, tick, 1, "flywheel:gpu/tick_prepare");
                dispatch(update, 1, tick, groups, "flywheel:gpu/tick_update");
                dispatch(update, 2, tick, 1, "flywheel:gpu/tick_finish");
            }
        } finally {
            GlCompat.popDebugGroup();
        }

        var frame = programs.getSimulationProgram(simulation.frameShader(), simulation);
        dispatch(frame, 0, tick, groups, "flywheel:gpu/frame_keys");
        GlCompat.pushDebugGroup("flywheel:gpu/radix_sort");
        try {
            sort(buffers);
        } finally {
            GlCompat.popDebugGroup();
        }
        bindSimulation(objects);
        dispatch(frame, 1, tick, groups, "flywheel:gpu/emission");

        GlCompat.pushDebugGroup("flywheel:gpu/publication");
        try {
            bind(0, buffers.control);
            bind(1, buffers.addresses);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, objects.frameDescriptorBuffer.handle());
            bind(3, buffers.keys[0]);
            var publish = programs.getComputeProgram(PUBLISH_PAGES);
            publish.bind();
            publish.setUInt("flw_capacity", capacity);
            glDispatchCompute((capacity + 127) / 128, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    private void uploadAddresses(GlBuffer buffer) {
        int pages = instancer.mapping.pageCount();
        var data = MemoryBlock.malloc((long) (capacity + pages) * 4);
        try {
            for (int index = 0; index < capacity; index++) {
                MemoryUtil.memPutInt(data.ptr() + (long) index * 4,
                        instancer.mapping.objectIndex2UintOffset(index));
            }
            for (int page = 0; page < pages; page++) {
                MemoryUtil.memPutInt(data.ptr() + (long) (capacity + page) * 4,
                        instancer.mapping.pageSlot(page));
            }
            buffer.upload(data);
        } finally {
            data.free();
        }
    }

    private void bindSimulation(ObjectStorage objects) {
        var buffers = storage;
        bind(0, buffers.state);
        bind(1, buffers.control);
        bind(2, buffers.parameters);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, objects.objectBuffer.handle());
        bind(4, buffers.addresses);
        bind(5, buffers.keys[0]);
        bind(6, buffers.order);
        bind(7, buffers.auxiliary);
    }

    private void dispatch(GlProgram program, int phase, int tick, int groups, String label) {
        GlCompat.pushDebugGroup(label);
        try {
            program.bind();
            program.setUInt("flw_phase", phase);
            program.setUInt("flw_tick", tick);
            program.setUInt("flw_capacity", capacity);
            glDispatchCompute(groups, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    private void sort(Storage buffers) {
        if (capacity <= 1) return;
        if (capacity <= 128) {
            bind(0, buffers.keys[0]);
            bind(2, buffers.control);
            programs.getComputeProgram(SORT_SMALL).bind();
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            return;
        }
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glClearNamedBufferSubData(buffers.control.handle(), GL_R32UI, 8, 4,
                GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        bind(0, buffers.keys[0]);
        bind(2, buffers.control);
        var check = programs.getComputeProgram(SORT_CHECK);
        check.bind();
        int groups = (capacity + 127) / 128;
        glDispatchCompute(groups, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        bind(3, buffers.histogram);
        bind(4, buffers.prefix);
        var count = programs.getComputeProgram(SORT_COUNT);
        var prefix = programs.getComputeProgram(SORT_PREFIX);
        var scatter = programs.getComputeProgram(SORT_SCATTER);
        for (int digit = 0; digit < 16; digit++) {
            bind(0, buffers.keys[digit & 1]);
            bind(1, buffers.keys[(digit & 1) ^ 1]);
            count.bind();
            count.setUInt("flw_digit", digit);
            glDispatchCompute(groups, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            prefix.bind();
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            scatter.bind();
            scatter.setUInt("flw_digit", digit);
            glDispatchCompute(groups, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
    }

    private static void bind(int index, GlBuffer buffer) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, index, buffer.handle());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!retired && initialized && invalidateReader != null) {
                snapshot(invalidateReader);
            }
        } finally {
            if (storage != null) {
                storage.delete();
            }
            parameters.free();
            auxiliary.free();
        }
    }

    private void snapshot(SnapshotReader reader) {
        var buffers = storage;
        var state = MemoryBlock.malloc(buffers.state.size());
        var control = MemoryBlock.malloc(buffers.control.size());
        var order = MemoryBlock.malloc(buffers.order.size());
        try {
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            download(buffers.state, state);
            download(buffers.control, control);
            download(buffers.auxiliary, auxiliary);
            download(buffers.order, order);
            reader.read(state.ptr(), control.ptr(), auxiliary.ptr(), order.ptr());
        } finally {
            state.free();
            control.free();
            order.free();
        }
    }

    private static void download(GlBuffer buffer, MemoryBlock data) {
        GlBufferType.COPY_READ_BUFFER.bind(buffer.handle());
        nglGetBufferSubData(GL_COPY_READ_BUFFER, 0, data.size(), data.ptr());
    }

    private final class Storage {
        final GlBuffer state = allocate((long) capacity * simulation.stateStride());
        final GlBuffer control = allocate(simulation.controlSize());
        final GlBuffer parameters = allocate(simulation.parameterSize());
        final GlBuffer auxiliary = allocate(simulation.auxiliarySize());
        final GlBuffer addresses = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
        final GlBuffer[] keys = {allocate((long) capacity * 16), allocate((long) capacity * 16)};
        final GlBuffer order = allocate((long) capacity * 4);
        final GlBuffer histogram = allocate((long) ((capacity + 127) / 128) * 16 * 4);
        final GlBuffer prefix = allocate(histogram.size());

        void delete() {
            for (var buffer : new GlBuffer[]{state, control, parameters, auxiliary, addresses,
                    keys[0], keys[1], order, histogram, prefix}) {
                buffer.delete();
            }
        }
    }

    private static GlBuffer allocate(long bytes) {
        var buffer = new GlBuffer(GlBufferUsage.DYNAMIC_COPY);
        buffer.upload(0, Math.max(bytes, 4));
        return buffer;
    }
}
