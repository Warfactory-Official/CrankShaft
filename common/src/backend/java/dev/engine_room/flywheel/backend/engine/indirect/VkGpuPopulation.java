package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.GpuPopulation;
import dev.engine_room.flywheel.api.instance.GpuSimulation;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

final class VkGpuPopulation implements GpuPopulationResources {
    private static final int USAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private static final Identifier SORT_CHECK = ResourceUtil.rl("internal/compute/sort_check.comp");
    private static final Identifier SORT_SMALL = ResourceUtil.rl("internal/compute/sort_small.comp");
    private static final Identifier SORT_COUNT = ResourceUtil.rl("internal/compute/sort_count.comp");
    private static final Identifier SORT_PREFIX = ResourceUtil.rl("internal/compute/sort_prefix.comp");
    private static final Identifier SORT_SCATTER = ResourceUtil.rl("internal/compute/sort_scatter.comp");
    private static final Identifier PUBLISH_PAGES = ResourceUtil.rl("internal/compute/publish_pages.comp");

    private final GpuSimulation simulation;
    private final GpuIndirectInstancer<?> instancer;
    private final VkPrograms programs;
    private final MemoryBlock parameters;
    private final MemoryBlock auxiliary;
    private final List<AuxiliaryRange> auxiliaryWrites = new ArrayList<>();
    private final VkDescriptorWriter writer = new VkDescriptorWriter();
    private final int capacity;
    private final int pageCount;
    private final int simulationGroups;
    private final int sortGroups;
    private int completedTick;
    private int targetTick;
    private boolean deleted;
    private boolean closed;
    @Nullable
    private SnapshotReader invalidationReader;
    @Nullable
    private Resources resources;

    VkGpuPopulation(GpuSimulation simulation, int capacity, GpuIndirectInstancer<?> instancer, VkPrograms programs) {
        this.simulation = simulation;
        this.capacity = capacity;
        this.instancer = instancer;
        this.programs = programs;
        pageCount = Mth.positiveCeilDiv(capacity, 32);
        simulationGroups = Mth.positiveCeilDiv(capacity, simulation.workGroupSize());
        sortGroups = Mth.positiveCeilDiv(capacity, 128);
        parameters = MemoryBlock.calloc(Math.max(4, simulation.parameterSize()), 1);
        auxiliary = MemoryBlock.calloc(Math.max(4, simulation.auxiliarySize()), 1);
    }

    @Override
    public void initialize(int tick) {
        if (resources != null) {
            throw new IllegalStateException("GPU population has already been prepared");
        }
        completedTick = tick;
    }

    @Override
    public void parameters(int targetTick, LongConsumer writer) {
        writer.accept(parameters.ptr());
        this.targetTick = targetTick;
    }

    @Override
    public void auxiliary(int offset, int bytes, LongConsumer writer) {
        writer.accept(auxiliary.ptr() + offset);
        if (bytes == 0) return;
        int first = offset;
        int end = offset + bytes;
        for (var iterator = auxiliaryWrites.iterator(); iterator.hasNext(); ) {
            AuxiliaryRange range = iterator.next();
            int rangeEnd = range.offset() + range.bytes();
            if (range.offset() <= end && rangeEnd >= first) {
                first = Math.min(first, range.offset());
                end = Math.max(end, rangeEnd);
                iterator.remove();
            }
        }
        auxiliaryWrites.add(new AuxiliaryRange(first, end - first));
    }

    @Override
    public void onInvalidate(GpuPopulation.SnapshotReader reader) {
        invalidationReader = reader;
    }

    @Override
    public boolean deleted() {
        return deleted;
    }

    @Override
    public void delete() {
        deleted = true;
    }

    void prepare(VkCommandBuffer cmd, VkObjectStorage storage, int parity) {
        boolean initialize = resources == null;
        if (initialize) {
            resources = new Resources();
        }
        Resources gpu = resources;
        Inputs inputs = gpu.inputs[parity];
        MemoryUtil.memCopy(parameters.ptr(), inputs.parameters.mappedAddress(), simulation.parameterSize());
        for (AuxiliaryRange range : auxiliaryWrites) {
            MemoryUtil.memCopy(auxiliary.ptr() + range.offset(), inputs.auxiliary.mappedAddress() + range.offset(),
                    range.bytes());
        }
        if (inputs.addressVersion != instancer.mapping.slotVersion()) {
            long address = inputs.addresses.mappedAddress();
            for (int slot = 0; slot < capacity; slot++) {
                MemoryUtil.memPutInt(address + (long) slot * Integer.BYTES,
                        instancer.mapping.objectIndex2UintOffset(slot));
            }
            long pageAddress = address + (long) capacity * Integer.BYTES;
            for (int page = 0; page < pageCount; page++) {
                MemoryUtil.memPutInt(pageAddress + (long) page * Integer.BYTES, instancer.mapping.pageSlot(page));
            }
            inputs.addressVersion = instancer.mapping.slotVersion();
        }
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_HOST_BIT
                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_HOST_WRITE_BIT | VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_TRANSFER_READ_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        if (initialize) {
            VK12.vkCmdFillBuffer(cmd, gpu.state.vkBuffer(), 0, gpu.state.sizeBytes(), 0);
            VK12.vkCmdFillBuffer(cmd, gpu.control.vkBuffer(), 0, gpu.control.sizeBytes(), 0);
            VK12.vkCmdFillBuffer(cmd, gpu.order.vkBuffer(), 0, gpu.order.sizeBytes(), 0);
            VK12.vkCmdFillBuffer(cmd, gpu.auxiliary.vkBuffer(), 0, gpu.auxiliary.sizeBytes(), 0);
            VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        }
        for (AuxiliaryRange range : auxiliaryWrites) {
            copy(cmd, inputs.auxiliary, gpu.auxiliary, range.offset(), range.bytes());
        }
        auxiliaryWrites.clear();
        transferToCompute(cmd);
        if (initialize) {
            simulation(cmd, programs.gpuSimulationPipeline(simulation.initializeShader(), simulation.doublePrecision(), simulation.defines()),
                    gpu, inputs, storage, 0, completedTick, 1);
        }
        int stopTick = (int) Math.min(targetTick, (long) completedTick + simulation.maxTicksPerFrame());
        VkComputePipeline tick = programs.gpuSimulationPipeline(simulation.tickShader(), simulation.doublePrecision(), simulation.defines());
        while (completedTick < stopTick) {
            int nextTick = completedTick + 1;
            simulation(cmd, tick, gpu, inputs, storage, 0, nextTick, 1);
            simulation(cmd, tick, gpu, inputs, storage, 1, nextTick, simulationGroups);
            simulation(cmd, tick, gpu, inputs, storage, 2, nextTick, 1);
            completedTick = nextTick;
        }
        VkComputePipeline frame = programs.gpuSimulationPipeline(simulation.frameShader(), simulation.doublePrecision(), simulation.defines());
        simulation(cmd, frame, gpu, inputs, storage, 0, completedTick, simulationGroups);
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        VK12.vkCmdFillBuffer(cmd, gpu.control.vkBuffer(), 8, 4, 0);
        transferToCompute(cmd);
        sort(cmd, gpu);
        simulation(cmd, frame, gpu, inputs, storage, 1, completedTick, simulationGroups);
        publish(cmd, gpu, inputs, storage);
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void simulation(VkCommandBuffer cmd, VkComputePipeline pipeline, Resources gpu, Inputs inputs,
                            VkObjectStorage storage, int phase, int tick, int groups) {
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        writer.storage(0, gpu.state).storage(1, gpu.control).storage(2, inputs.parameters)
                .storage(3, storage.objectBuffer()).storage(4, inputs.addresses).storage(5, gpu.keys[0])
                .storage(6, gpu.order).storage(7, gpu.auxiliary);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdPushConstants(cmd, pipeline.layout().pipelineLayout(), VK12.VK_SHADER_STAGE_COMPUTE_BIT,
                    0, stack.ints(phase, tick, capacity));
        }
        VK12.vkCmdDispatch(cmd, groups, 1, 1);
        computeBarrier(cmd);
    }

    private void sort(VkCommandBuffer cmd, Resources gpu) {
        if (capacity <= 1) return;
        if (capacity <= 128) {
            sortDispatch(cmd, programs.gpuSortPipeline(SORT_SMALL), gpu, 0, 0, 1);
            return;
        }
        sortDispatch(cmd, programs.gpuSortPipeline(SORT_CHECK), gpu, 0, 0, sortGroups);
        for (int digit = 0; digit < 16; digit++) {
            int source = digit & 1;
            sortDispatch(cmd, programs.gpuSortPipeline(SORT_COUNT), gpu, source, digit, sortGroups);
            sortDispatch(cmd, programs.gpuSortPipeline(SORT_PREFIX), gpu, source, digit, 1);
            sortDispatch(cmd, programs.gpuSortPipeline(SORT_SCATTER), gpu, source, digit, sortGroups);
        }
    }

    private void sortDispatch(VkCommandBuffer cmd, VkComputePipeline pipeline, Resources gpu,
                              int source, int digit, int groups) {
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        writer.storage(0, gpu.keys[source]).storage(1, gpu.keys[source ^ 1]).storage(2, gpu.control)
                .storage(3, gpu.histograms).storage(4, gpu.prefixes);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdPushConstants(cmd, pipeline.layout().pipelineLayout(), VK12.VK_SHADER_STAGE_COMPUTE_BIT,
                    0, stack.ints(digit));
        }
        VK12.vkCmdDispatch(cmd, groups, 1, 1);
        computeBarrier(cmd);
    }

    private void publish(VkCommandBuffer cmd, Resources gpu, Inputs inputs, VkObjectStorage storage) {
        VkComputePipeline pipeline = programs.gpuPublishPipeline(PUBLISH_PAGES);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        writer.storage(0, gpu.control).storage(1, inputs.addresses).storage(2, storage.frameDescriptorBuffer())
                .storage(3, gpu.keys[0]);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdPushConstants(cmd, pipeline.layout().pipelineLayout(), VK12.VK_SHADER_STAGE_COMPUTE_BIT,
                    0, stack.ints(capacity));
        }
        VK12.vkCmdDispatch(cmd, sortGroups, 1, 1);
    }

    private static void computeBarrier(VkCommandBuffer cmd) {
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void transferToCompute(VkCommandBuffer cmd) {
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!deleted && resources != null && invalidationReader != null) {
            snapshot(resources, invalidationReader);
        }
        deleted = true;
        closed = true;
        if (resources != null) {
            resources.delete();
        }
        parameters.free();
        auxiliary.free();
    }

    private void snapshot(Resources gpu, SnapshotReader reader) {
        VkBuffer state = new VkBuffer(USAGE, gpu.state.sizeBytes());
        VkBuffer control = new VkBuffer(USAGE, gpu.control.sizeBytes());
        VkBuffer order = new VkBuffer(USAGE, gpu.order.sizeBytes());
        VkBuffer auxiliary = new VkBuffer(USAGE, gpu.auxiliary.sizeBytes());
        try {
            VkCommandBuffer cmd = VkContext.beginCommands();
            VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_TRANSFER_READ_BIT);
            copy(cmd, gpu.state, state);
            copy(cmd, gpu.control, control);
            copy(cmd, gpu.order, order);
            copy(cmd, gpu.auxiliary, auxiliary);
            VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_HOST_READ_BIT);
            VkContext.submitCommands(cmd);
            VkContext.encoder().submit();
            VkContext.device().graphicsQueue().waitIdle();
            reader.read(state.mappedAddress(), control.mappedAddress(), auxiliary.mappedAddress(), order.mappedAddress());
        } finally {
            state.delete();
            control.delete();
            order.delete();
            auxiliary.delete();
        }
    }

    private static void copy(VkCommandBuffer cmd, VkBuffer source, VkBuffer target) {
        copy(cmd, source, target, 0, source.sizeBytes());
    }

    private static void copy(VkCommandBuffer cmd, VkBuffer source, VkBuffer target, long offset, long bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer regions = VkBufferCopy.calloc(1, stack);
            regions.get(0).set(offset, offset, bytes);
            VK12.vkCmdCopyBuffer(cmd, source.vkBuffer(), target.vkBuffer(), regions);
        }
    }

    private record AuxiliaryRange(int offset, int bytes) {
    }

    private final class Resources {
        final VkBuffer state = new VkBuffer(USAGE, (long) capacity * simulation.stateStride(), true);
        final VkBuffer control = new VkBuffer(USAGE, simulation.controlSize(), true);
        final VkBuffer[] keys = {new VkBuffer(USAGE, (long) capacity * 16, true),
                new VkBuffer(USAGE, (long) capacity * 16, true)};
        final VkBuffer order = new VkBuffer(USAGE, (long) capacity * Integer.BYTES, true);
        final VkBuffer auxiliary = new VkBuffer(USAGE, Math.max(4, simulation.auxiliarySize()), true);
        final VkBuffer histograms = new VkBuffer(USAGE, (long) sortGroups * 16 * Integer.BYTES, true);
        final VkBuffer prefixes = new VkBuffer(USAGE, (long) sortGroups * 16 * Integer.BYTES, true);
        final Inputs[] inputs = {new Inputs(), new Inputs()};

        void delete() {
            state.delete();
            control.delete();
            keys[0].delete();
            keys[1].delete();
            order.delete();
            auxiliary.delete();
            histograms.delete();
            prefixes.delete();
            inputs[0].delete();
            inputs[1].delete();
        }
    }

    private final class Inputs {
        final VkBuffer parameters = new VkBuffer(USAGE, Math.max(4, simulation.parameterSize()));
        final VkBuffer auxiliary = new VkBuffer(USAGE, Math.max(4, simulation.auxiliarySize()));
        final VkBuffer addresses = new VkBuffer(USAGE, (long) (capacity + pageCount) * Integer.BYTES);
        int addressVersion = -1;

        void delete() {
            parameters.delete();
            auxiliary.delete();
            addresses.delete();
        }
    }
}
