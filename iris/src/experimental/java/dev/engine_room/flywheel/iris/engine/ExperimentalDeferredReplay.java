package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.experimental.mixin.ExperimentalCompositePassMixin;
import dev.engine_room.flywheel.iris.experimental.mixin.ExperimentalComputeProgramAccessor;
import dev.engine_room.flywheel.iris.experimental.mixin.ExperimentalStorageBufferAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.SyncCommand;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ComputeProgram;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Minecraft;
import org.joml.Vector3i;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPU-gated multilayer replay with asynchronous demand feedback and pack-owned stages.
 */
public final class ExperimentalDeferredReplay implements AutoCloseable {
    private final List<Object> nativePasses;
    private final List<Object> stages;
    private final List<ComputeProgram> computes = new ArrayList<>();
    private final RenderTargets targets;
    private final ShaderStorageBufferHolder storage;
    private final ExperimentalReplayResources resources;
    private final ExperimentalReplayProgram commandsProgram;
    private final ExperimentalReplayProgram gateProgram;
    private final GpuBuffer indices;
    private final GpuBuffer[] readback = new GpuBuffer[3];
    private final GpuFence[] fences = new GpuFence[3];
    private final int[] readbackBounds = new int[3];
    private final int words;
    private final int firstStage;
    private final int afterStage;
    private final GpuBuffer groups;
    private final ByteBuffer groupData;
    private final ByteBuffer controlData = MemoryUtil.memCalloc(16).order(ByteOrder.nativeOrder());
    private final List<GpuBufferSlice> groupBinding;
    private final Vector3i dispatchOne = new Vector3i(1);
    private final Vector3i commandGroups = new Vector3i();
    private GpuBuffer commandBuffer;
    private GpuBufferSlice[] drawCommands = new GpuBufferSlice[0];
    private boolean groupsUploaded;
    private int bound = 1;
    private int readbackSlot;
    private int shrinkLayers;
    private int shrinkPool;
    private int lastFallback;
    private boolean open;

    private ExperimentalDeferredReplay(IrisRenderingPipeline parent, RenderTargets targets, List<?> passes,
                                       List<Object> stages, DeferredOitProfile profile) {
        this.targets = targets;
        storage = parent.getStorageBuffers();
        nativePasses = passes.stream().map(pass -> (Object) pass).toList();
        this.stages = stages;
        firstStage = nativePasses.indexOf(stages.getFirst());
        afterStage = nativePasses.indexOf(stages.getLast()) + 1;
        for (var stage : stages)
            for (var compute : ((ExperimentalCompositePassMixin) stage).flywheel$computes())
                if (compute != null) computes.add(compute);
        words = 5 + computes.size() * 3;
        int groupBytes = Math.max(16, computes.size() * 16);
        groupData = MemoryUtil.memCalloc(groupBytes).order(ByteOrder.nativeOrder());
        groups = Iris.getRenderBackend().createStorageBuffer("flywheel replay compute groups", groupBytes, null);
        groupBinding = List.of(groups.slice());
        resources = switch (profile) {
            case SUNDIAL -> new ExperimentalSundialReplay(parent, targets, stages.getFirst(), stages.getLast());
            case SEUS, SEUS_HRR3 ->
                    new ExperimentalSeusReplay(parent, targets, stages, profile == DeferredOitProfile.SEUS_HRR3);
            default -> throw new UnsupportedOperationException(
                    "Experimental deferred replay resources missing for " + profile);
        };
        commandsProgram = new ExperimentalReplayProgram(parent, "native_layer_commands.comp", true, false,
                ShaderAssembly.NO_EXTRA, Set.of("_FlwReplayGroups"));
        gateProgram = new ExperimentalReplayProgram(parent, "native_layer_gate.comp", true, false,
                ShaderAssembly.NO_EXTRA, Set.of());
        ByteBuffer indexData = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        indexData.asShortBuffer().put(new short[]{0, 1, 2, 2, 3, 0});
        indices = RenderSystem.getDevice().createBuffer(() -> "flywheel replay quad", GpuBuffer.USAGE_INDEX, indexData);
        for (int i = 0; i < readback.length; i++)
            readback[i] = RenderSystem.getDevice().createBuffer(
                    () -> "flywheel replay demand", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 16);
    }

    public static ExperimentalDeferredReplay create(IrisRenderingPipeline parent, RenderTargets targets,
                                                    List<?> passes, DeferredOitProfile profile) {
        var stages = new ArrayList<Object>();
        for (Object entry : passes) {
            var pass = entry;
            boolean selected = profile == DeferredOitProfile.SUNDIAL ? ((ExperimentalCompositePassMixin) pass).flywheel$name()
                                                                                                              .matches(
                                                                                                                      "composite[1-6]")
                    : ((ExperimentalCompositePassMixin) pass).flywheel$name()
                                                             .equals("composite") || ((ExperimentalCompositePassMixin) pass).flywheel$name()
                                                                                                                            .matches(
                                                                                                                                    "composite[1-4]");
            if (selected) stages.add(pass);
        }
        if (stages.isEmpty()) return null;
        String first = profile == DeferredOitProfile.SUNDIAL ? "composite1" : "composite";
        if (!((ExperimentalCompositePassMixin) stages.getFirst()).flywheel$name().equals(first))
            throw new IllegalStateException("Deferred replay entry changed");
        return new ExperimentalDeferredReplay(parent, targets, passes, stages, profile);
    }

    public void render() {
        boolean previous = ImmediateState.temporarilyIgnorePass;
        ImmediateState.temporarilyIgnorePass = true;
        try {
            for (int index = 0; index < nativePasses.size(); index++) {
                if (index == firstStage && BackendManagerImpl.isBackendOn()) replay();
                if (index == afterStage) finish();
                nativePass(nativePasses.get(index), null, -1);
            }
        } finally {
            finish();
            ImmediateState.temporarilyIgnorePass = previous;
        }
    }

    private void replay() {
        resources.prepareCapture();
        harvest();
        if (fences[readbackSlot] == null) {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            var source = storage.getBuffer(2).slice();
            Iris.getRenderBackend().createSync().read(source, SyncCommand.Access.TRANSFER)
                .nextIs(SyncCommand.Stage.TRANSFER).run();
            encoder.copyToBuffer(source, readback[readbackSlot].slice());
            fences[readbackSlot] = encoder.createFence();
            readbackBounds[readbackSlot] = bound;
        }
        readbackSlot = (readbackSlot + 1) % readback.length;
        if (bound == 1) return;
        ensureCommands();
        resources.snapshot(indices, command(0));
        open = true;
        ExperimentalReplayState.replaying = true;
        for (int layer = bound - 1; layer > 0; --layer) {
            ExperimentalReplayState.layer = layer;
            var command = command(layer);
            resources.materialize(layer, indices, command);
            int computeIndex = 0;
            for (var stage : stages) {
                nativePass(stage, command, computeIndex);
                for (var compute : ((ExperimentalCompositePassMixin) stage).flywheel$computes())
                    if (compute != null) computeIndex++;
            }
            resources.advance(layer, indices, command);
        }
        ExperimentalReplayState.layer = 0;
        resources.materialize(0, indices, command(0));
    }

    private void ensureCommands() {
        var commands = storage.getBuffer(4);
        long bytes = (long) bound * words * 4;
        if (commands.getSize() < bytes) replace(4, bytes);
        if (commandBuffer != commands.buffer() || drawCommands.length < bound) {
            commandBuffer = commands.buffer();
            drawCommands = new GpuBufferSlice[bound];
            for (int layer = 0; layer < bound; layer++)
                drawCommands[layer] = commandBuffer.slice((long) layer * words * 4, 20);
        }
        boolean changed = !groupsUploaded;
        for (int i = 0; i < computes.size(); i++) {
            var size = computes.get(i).getWorkGroups(targets.getCurrentWidth(), targets.getCurrentHeight());
            int offset = i * 16;
            if (groupData.getInt(offset) != size.x || groupData.getInt(offset + 4) != size.y
                    || groupData.getInt(offset + 8) != size.z) {
                groupData.putInt(offset, size.x).putInt(offset + 4, size.y).putInt(offset + 8, size.z);
                changed = true;
            }
        }
        if (changed) {
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(groups.slice(), groupData);
            groupsUploaded = true;
        }
        commandsProgram.dispatch(Map.of("_FlwReplayGroups", groupBinding,
                        "_FlwReplayControl", List.of(control(bound, words, computes.size()))),
                commandGroups.set((bound + 63) / 64, 1, 1));
    }

    private GpuBufferSlice command(int layer) {
        return drawCommands[layer];
    }

    private GpuBufferSlice control(int first, int second, int third) {
        controlData.clear().putInt(first).putInt(second).putInt(third).putInt(0).flip();
        // uploadGpu copies into the current submission's storage before this CPU buffer is reused.
        return RenderSystem.getDevice().createCommandEncoder().transientMemory().uploadGpu(controlData,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
    }

    private void nativePass(Object pass, GpuBufferSlice command, int computeIndex) {
        var mc = Minecraft.getInstance();
        long tick = mc.level.getGameTime(), frame = SystemTimeUniforms.COUNTER.getAsInt();
        for (var compute : ((ExperimentalCompositePassMixin) pass).flywheel$computes()) {
            if (compute == null) continue;
            if (command == null) compute.dispatch(targets.getCurrentWidth(), targets.getCurrentHeight());
            else {
                long offset = command.offset() + 20 + computeIndex++ * 12L;
                var original = ((ExperimentalComputeProgramAccessor) (Object) compute).flywheel$indirect();
                if (original != null) {
                    var destination = storage.getBuffer(4).buffer().slice(offset, 12);
                    Iris.getRenderBackend().createSync().read(original.buffer(), SyncCommand.Access.TRANSFER)
                        .write(destination, SyncCommand.Access.TRANSFER).nextIs(SyncCommand.Stage.TRANSFER).run();
                    RenderSystem.getDevice().createCommandEncoder()
                                .copyToBuffer(original.buffer().slice(original.offset(), 12), destination);
                    gateProgram.dispatch(Map.of("_FlwReplayControl", List.of(control(ExperimentalReplayState.layer,
                            Math.toIntExact(offset / 4), 0))), dispatchOne);
                }
                compute.flywheel$dispatchIndirect(
                        new FilledIndirectPointer(storage, 4, offset), tick, frame);
            }
        }
        if (((ExperimentalCompositePassMixin) pass).flywheel$program() == null) return;
        for (int target : ((ExperimentalCompositePassMixin) pass).flywheel$mipmaps())
            targets.get(target)
                   .generateMipmaps(((ExperimentalCompositePassMixin) pass).flywheel$readAlt().contains(target));
        if (command == null) ((ExperimentalCompositePassMixin) pass).flywheel$program()
                                                                    .draw(((ExperimentalCompositePassMixin) pass).flywheel$framebuffer()
                                                                                                                 .getColorViews(),
                                                                            ((ExperimentalCompositePassMixin) pass).flywheel$viewport(),
                                                                            tick,
                                                                            frame);
        else ((ExperimentalCompositePassMixin) pass).flywheel$program().flywheel$drawIndirect(
                ((ExperimentalCompositePassMixin) pass).flywheel$framebuffer().getColorViews(),
                ((ExperimentalCompositePassMixin) pass).flywheel$viewport(), command, tick, frame);
    }

    private void harvest() {
        for (int i = 0; i < fences.length; i++) {
            int slot = (readbackSlot + i) % fences.length;
            var fence = fences[slot];
            if (fence == null || !fence.awaitCompletion(0)) continue;
            fence.close();
            fences[slot] = null;
            long demand;
            int layers, fallback;
            try (var mapped = readback[slot].map(true, false)) {
                var data = mapped.data().order(ByteOrder.nativeOrder());
                demand = Integer.toUnsignedLong(data.getInt(0));
                fallback = data.getInt(4);
                layers = data.getInt(8);
                if (layers > readbackBounds[slot]) fallback |= 2;
            }
            if (fallback != lastFallback) {
                if (fallback == 0) FlwBackend.LOGGER.info("Experimental deferred transparency sorting restored");
                else FlwBackend.LOGGER.warn(
                        "Experimental deferred transparency uses native ordering: flags={}, requestedSlots={}, layers={}",
                        fallback, demand, layers);
                lastFallback = fallback;
            }
            int wanted = Math.min(64, Integer.highestOneBit(Math.max(1, layers) * 2 - 1));
            if (wanted > bound) {
                bound = wanted;
                shrinkLayers = 0;
            } else if (wanted == bound) shrinkLayers = 0;
            else if (++shrinkLayers >= 60) {
                bound >>= 1;
                shrinkLayers = 0;
            }
            long maximum = VkContext.isVulkanHost() ? VkCaps.MAX_STORAGE_BUFFER_RANGE
                    : GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
            long granule = 1 << 18;
            long wantedNodes = Math.min(
                    Math.min(maximum / 16, (long) targets.getCurrentWidth() * targets.getCurrentHeight() * 16),
                    Math.max(1 << 16, ((demand + demand / 4 + granule - 1) / granule) * granule));
            long current = storage.getBuffer(1).getSize() / 16;
            if (wantedNodes > current || wantedNodes * 2 <= current && ++shrinkPool >= 60) {
                replace(1, wantedNodes * 16);
                controlData.clear().putInt(1).flip();
                RenderSystem.getDevice().createCommandEncoder()
                            .writeToBuffer(storage.getBuffer(2).buffer().slice(4, 4), controlData);
                shrinkPool = 0;
            } else if (wantedNodes * 2 > current) shrinkPool = 0;
        }
    }

    private void replace(int index, long bytes) {
        var buffer = storage.getBuffer(index);
        var old = buffer.buffer();
        var replacement = Iris.getRenderBackend().createStorageBuffer("flywheel replay storage " + index, bytes, null);
        ((ExperimentalStorageBufferAccessor) (Object) buffer).flywheel$buffer(replacement);
        Iris.getRenderBackend().retire(old::close);
    }

    private void finish() {
        if (open) {
            open = false;
            resources.restore();
        }
        ExperimentalReplayState.layer = 0;
        ExperimentalReplayState.replaying = false;
    }

    @Override
    public void close() {
        finish();
        resources.close();
        commandsProgram.close();
        gateProgram.close();
        Iris.getRenderBackend().retire(indices::close);
        Iris.getRenderBackend().retire(groups::close);
        MemoryUtil.memFree(groupData);
        MemoryUtil.memFree(controlData);
        for (var buffer : readback) Iris.getRenderBackend().retire(buffer::close);
        for (var fence : fences) if (fence != null) Iris.getRenderBackend().retire(fence::close);
    }
}
