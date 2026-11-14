package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.iris.mixin.ShaderStorageBufferAccessor;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;

/**
 * Capture node pool of an inline deferred resolve, owned by one Iris CompositeRenderer on the render thread.
 * Fenced demand readbacks taken after the composite sequence resize the Iris-owned node buffer at the next capture
 * boundary. An overflowing frame resolves only its native front surfaces. A frame with the engine off releases the pool
 * to its minimum.
 */
public final class DeferredOitPool implements AutoCloseable {
    private static final int SLOTS = 3;
    // Capture metadata bit 31 keeps linked storage warm while sorted blending bypasses its allocation counter.
    private static final int SORTED_BLEND_FRAME = 0x80000000;
    private final RenderTargets targets;
    private final ShaderStorageBuffer nodes;
    private final ShaderStorageBuffer counts;
    private final Spec spec;
    private final long maxStorageNodes;
    private final int[] readback = new int[SLOTS];
    private final ByteBuffer[] readbackData = new ByteBuffer[SLOTS];
    private final long[] readbackFence = new long[SLOTS];
    private final long[] readbackPools = new long[SLOTS];
    private final DeferredDemandBudget demandBudget = new DeferredDemandBudget();
    private int slot;
    private long poolNodes;
    private long pendingPoolNodes;
    private int lastOverflow;
    private boolean capturing;

    public DeferredOitPool(Spec spec, RenderTargets targets, ShaderStorageBuffer[] storage) {
        this.spec = spec;
        this.targets = targets;
        nodes = storage[spec.nodesBinding()];
        counts = storage[spec.countsBinding()];
        maxStorageNodes = GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) / spec.nodeBytes();
        poolNodes = GL45C.glGetNamedBufferParameteri64(nodes.getId(), GL15C.GL_BUFFER_SIZE) / spec.nodeBytes();
        int flags = GL30C.GL_MAP_READ_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT;
        try {
            for (int i = 0; i < SLOTS; i++) {
                readback[i] = GL45C.glCreateBuffers();
                GL45C.glNamedBufferStorage(readback[i], 16, flags);
                readbackData[i] = GL45C.glMapNamedBufferRange(readback[i], 0, 16, flags);
            }
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    /**
     * Applies the latest completed frame's pool request before the native capture clear.
     */
    public void beginCaptureFrame() {
        capturing = BackendManagerImpl.isBackendOn();
        long capacity = capturing ? pendingPoolNodes : spec.minNodes();
        pendingPoolNodes = 0;
        if (capturing) capacity = Math.max(capacity == 0 ? poolNodes : capacity,
                spec.minimumNodes(targets.getCurrentWidth(), targets.getCurrentHeight()));
        capacity = Math.min(capacity, maxPoolNodes());
        if (capacity == poolNodes) return;
        GlCompat.pushDebugGroup("flywheel:iris/pool_resize");
        try {
            int buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(buffer, capacity * spec.nodeBytes(), 0);
            int old = nodes.getId();
            ((ShaderStorageBufferAccessor) nodes).flywheel$setId(buffer);
            GlStateManager._glDeleteBuffers(old);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, nodes.getIndex(), buffer);
            poolNodes = capacity;
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    /**
     * Samples a capturing frame's counters once every composite consumer has been recorded.
     */
    public void finishFrame(boolean completed) {
        if (!completed) return;
        harvest();
        if (capturing && readbackFence[slot] == 0) {
            GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL45C.glCopyNamedBufferSubData(counts.getId(), readback[slot], 0, 0, 16);
            readbackPools[slot] = poolNodes;
            readbackFence[slot] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        slot = (slot + 1) % SLOTS;
    }

    private long maxPoolNodes() {
        return Math.min(maxStorageNodes,
                spec.maximumNodes(targets.getCurrentWidth(), targets.getCurrentHeight()));
    }

    private void harvest() {
        long peakDemand = 0;
        boolean harvested = false;
        boolean preserveCapacity = false;
        for (int i = 0; i < SLOTS; i++) {
            long fence = readbackFence[i];
            if (fence == 0) continue;
            int status = GL32C.glClientWaitSync(fence, 0, 0);
            if (status != GL32C.GL_ALREADY_SIGNALED && status != GL32C.GL_CONDITION_SATISFIED) continue;
            GL32C.glDeleteSync(fence);
            readbackFence[i] = 0;
            ByteBuffer data = readbackData[i];
            long demand = Integer.toUnsignedLong(data.getInt(0));
            int overflow = data.getInt(4);
            preserveCapacity |= (data.getInt(12) & SORTED_BLEND_FRAME) != 0;
            peakDemand = Math.max(peakDemand, demand);
            harvested = true;
            if (overflow != lastOverflow) {
                if (overflow != 0) FlwBackend.LOGGER.warn(
                        "Deferred transparency uses native ordering: requestedSlots={}, capture capacity={} slots, pool limit={} slots",
                        demand, readbackPools[i], maxPoolNodes());
                else FlwBackend.LOGGER.info("Deferred transparency sorting restored");
                lastOverflow = overflow;
            }
        }
        if (!harvested) return;
        long maximum = maxPoolNodes();
        long wanted = DeferredDemandBudget.nodesForDemand(peakDemand, maximum,
                spec.minimumNodes(targets.getCurrentWidth(), targets.getCurrentHeight()), spec.nodeGranule());
        if (preserveCapacity) {
            demandBudget.resetQuietPeriod();
            if (wanted <= poolNodes) {
                pendingPoolNodes = 0;
                return;
            }
        }
        long selected = demandBudget.poolTarget(poolNodes, wanted, maximum, System.nanoTime());
        pendingPoolNodes = selected == poolNodes ? 0 : selected;
    }

    @Override
    public void close() {
        for (int i = 0; i < SLOTS; i++) {
            if (readbackFence[i] != 0) GL32C.glDeleteSync(readbackFence[i]);
            if (readback[i] != 0) GlStateManager._glDeleteBuffers(readback[i]);
            readbackFence[i] = 0;
            readback[i] = 0;
        }
    }

    /**
     * Storage ABI: node and counter bindings (counters = slot demand, overflow, maximum layers, metadata), the
     * capture depth snapshot's binding, slot size and capacity policy. A particle table reserves three slots for each
     * pixel of the packed half-resolution quadrant, including its edge, before linked capture nodes.
     */
    public record Spec(int nodesBinding, int countsBinding, int snapshotBinding, int nodeBytes, int minNodes,
                       int nodeGranule, int maxNodesPerPixel, boolean particleTable) {
        public Spec(int nodesBinding, int countsBinding, int snapshotBinding, int nodeBytes, int minNodes,
                    int nodeGranule, int maxNodesPerPixel) {
            this(nodesBinding, countsBinding, snapshotBinding, nodeBytes, minNodes, nodeGranule, maxNodesPerPixel, false);
        }

        public long minimumNodes(int width, int height) {
            return minNodes + reservedNodes(width, height);
        }

        public long maximumNodes(int width, int height) {
            return (long) maxNodesPerPixel * width * height + reservedNodes(width, height);
        }

        private long reservedNodes(int width, int height) {
            return particleTable ? 3L * (width / 2 + 1) * (height / 2 + 1) : 0;
        }
    }
}
