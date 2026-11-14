package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.iris.experimental.mixin.ExperimentalStorageBufferAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.SyncCommand;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteOrder;

/**
 * Experimental-Iris counterpart of {@link DeferredOitPool}: fenced demand readbacks after the composite sequence
 * resize the Iris-owned node buffer at the next capture boundary. A frame with the engine off releases the pool to its
 * minimum. Render thread only.
 */
public final class ExperimentalDeferredPool implements AutoCloseable {
    private final RenderTargets targets;
    private final ShaderStorageBufferHolder storage;
    private final DeferredOitPool.Spec spec;
    private final GpuBuffer[] readback = new GpuBuffer[3];
    private final GpuFence[] fences = new GpuFence[3];
    private final long[] readbackPools = new long[3];
    private final DeferredDemandBudget demandBudget = new DeferredDemandBudget();
    private int slot;
    private long poolNodes;
    private long pendingPoolNodes;
    private int lastOverflow;
    private boolean capturing;

    public ExperimentalDeferredPool(IrisRenderingPipeline parent, RenderTargets targets, DeferredOitPool.Spec spec) {
        this.targets = targets;
        this.spec = spec;
        storage = parent.getStorageBuffers();
        poolNodes = storage.getBuffer(spec.nodesBinding()).getSize() / spec.nodeBytes();
        for (int i = 0; i < readback.length; i++)
            readback[i] = RenderSystem.getDevice().createBuffer(
                    () -> "flywheel deferred demand", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 16);
    }

    /**
     * Applies the latest completed frame's pool request before the native begin passes clear capture storage.
     */
    public void beginCaptureFrame() {
        capturing = BackendManagerImpl.isBackendOn();
        long capacity = capturing ? pendingPoolNodes : spec.minNodes();
        pendingPoolNodes = 0;
        if (capturing) capacity = Math.max(capacity == 0 ? poolNodes : capacity,
                spec.minimumNodes(targets.getCurrentWidth(), targets.getCurrentHeight()));
        capacity = Math.min(capacity, maxPoolNodes());
        if (capacity == poolNodes) return;
        var buffer = storage.getBuffer(spec.nodesBinding());
        var old = buffer.buffer();
        var replacement = Iris.getRenderBackend().createStorageBuffer("flywheel deferred nodes",
                capacity * spec.nodeBytes(), null);
        ((ExperimentalStorageBufferAccessor) (Object) buffer).flywheel$buffer(replacement);
        Iris.getRenderBackend().retire(old::close);
        poolNodes = capacity;
    }

    /**
     * Samples a capturing frame's counters once every composite consumer has been recorded.
     */
    public void finishFrame(boolean completed) {
        if (!completed) return;
        harvest();
        if (capturing && fences[slot] == null) {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            var source = storage.getBuffer(spec.countsBinding()).buffer().slice(0, 16);
            Iris.getRenderBackend().createSync().read(source, SyncCommand.Access.TRANSFER)
                .nextIs(SyncCommand.Stage.TRANSFER).run();
            encoder.copyToBuffer(source, readback[slot].slice());
            fences[slot] = encoder.createFence();
            readbackPools[slot] = poolNodes;
        }
        slot = (slot + 1) % readback.length;
    }

    private long maxPoolNodes() {
        long maximum = VkContext.isVulkanHost() ? VkCaps.MAX_STORAGE_BUFFER_RANGE
                : GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        return Math.min(maximum / spec.nodeBytes(),
                spec.maximumNodes(targets.getCurrentWidth(), targets.getCurrentHeight()));
    }

    private void harvest() {
        long peakDemand = 0;
        boolean harvested = false;
        for (int i = 0; i < fences.length; i++) {
            var fence = fences[i];
            if (fence == null || !fence.awaitCompletion(0)) continue;
            fence.close();
            fences[i] = null;
            long demand;
            int overflow;
            try (var mapped = readback[i].map(true, false)) {
                var data = mapped.data().order(ByteOrder.nativeOrder());
                demand = Integer.toUnsignedLong(data.getInt(0));
                overflow = data.getInt(4);
            }
            peakDemand = Math.max(peakDemand, demand);
            harvested = true;
            if (overflow != lastOverflow) {
                if (overflow != 0) FlwBackend.LOGGER.warn(
                        "Experimental deferred transparency uses native ordering: requestedSlots={}, capture capacity={} slots, pool limit={} slots",
                        demand, readbackPools[i], maxPoolNodes());
                else FlwBackend.LOGGER.info("Experimental deferred transparency sorting restored");
                lastOverflow = overflow;
            }
        }
        if (!harvested) return;
        long maximum = maxPoolNodes();
        long wanted = DeferredDemandBudget.nodesForDemand(peakDemand, maximum,
                spec.minimumNodes(targets.getCurrentWidth(), targets.getCurrentHeight()), spec.nodeGranule());
        long selected = demandBudget.poolTarget(poolNodes, wanted, maximum, System.nanoTime());
        pendingPoolNodes = selected == poolNodes ? 0 : selected;
    }

    @Override
    public void close() {
        for (var buffer : readback) Iris.getRenderBackend().retire(buffer::close);
        for (var fence : fences) if (fence != null) Iris.getRenderBackend().retire(fence::close);
    }
}
