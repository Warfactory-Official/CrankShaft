package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class DeferredOitRenderer implements AutoCloseable {
    private static final int MAX_LAYERS = 64;
    private static final int READBACK_SLOTS = 3;
    private static final int SHRINK_READBACKS = 60;
    private static final int RELEASE_READBACKS = 600;
    private static final int COMPUTE_BARRIER = GL43.GL_SHADER_STORAGE_BARRIER_BIT
            | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT;
    private static final int[] INVALID_CAPTURE = {1};
    private final DeferredReplayResources resources;
    private final DeferredReplayBuffers storage;
    private final DeferredReplayPass[] passes;
    private final boolean[] masked;
    private final DeferredReplayPass after;
    private final DeferredReplayPlan.Storage abi;
    private final List<DeferredReplayPass.Compute> computes = new ArrayList<>();
    private final Int2ObjectOpenHashMap<LayerUniforms> uniforms = new Int2ObjectOpenHashMap<>();
    private final int[] computeStarts;
    private final int resourceComputeGroupSize;
    private final int commandWords;
    private final long maxStorageNodes;
    private final int[] readback = new int[READBACK_SLOTS];
    private final ByteBuffer[] readbackData = new ByteBuffer[READBACK_SLOTS];
    private final long[] readbackFence = new long[READBACK_SLOTS];
    private final int[] readbackBounds = new int[READBACK_SLOTS];
    private GlProgram commandsProgram;
    private GlProgram indirectGate;
    private DeferredReplayQuad quad;
    private DeferredReplayMask mask;
    private int commands;
    private int readbackSlot;
    private int layerBound = 1;
    private int commandLayers = 1;
    private int shrinkReadbacks;
    private int idleReadbacks;
    private int poolShrinks;
    private int lastFallback;
    private long poolNodes;
    private int nearestStage;
    private DeferredReplayPass pendingNearest;
    private boolean frameOpen;

    DeferredOitRenderer(DeferredReplayPlan plan, List<? extends DeferredReplayPass> nativePasses,
                        DeferredReplayBuffers storage) {
        plan.validatePasses(nativePasses);
        resources = plan.resources();
        this.storage = storage;
        abi = plan.storage();
        after = plan.after();
        passes = new DeferredReplayPass[plan.stages().length];
        masked = new boolean[passes.length];
        computeStarts = new int[passes.length + 1];
        for (var index = 0; index < passes.length; index++) {
            var stage = plan.stages()[index];
            masked[index] = stage.tileMasked();
            if (masked[index] && stage.pass().program() == 0)
                throw new IllegalArgumentException("Tile masks require a graphics pass: " + stage.pass().name());
            passes[index] = stage.pass();
            registerUniforms(passes[index].program());
            computeStarts[index] = computes.size();
            for (var compute : passes[index].computes()) {
                computes.add(compute);
                registerUniforms(compute.program());
            }
        }
        computeStarts[passes.length] = computes.size();
        resourceComputeGroupSize = resources.computeGroupSize();
        commandWords = 5 + computes.size() * 3 + (resourceComputeGroupSize == 0 ? 0 : 3);
        maxStorageNodes = GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) / abi.nodeBytes();
        poolNodes = GL45.glGetNamedBufferParameteri64(storage.buffer(abi.nodesBinding()),
                GL15.GL_BUFFER_SIZE) / abi.nodeBytes();
        try {
            commandsProgram = DeferredReplayShaders.compute("layer_commands.comp", this::defines);
            quad = new DeferredReplayQuad();
            for (var enabled : masked) if (enabled) {
                mask = new DeferredReplayMask(abi, plan.tileRadius());
                break;
            }
            commands = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(commands, commandWords * 4L, 0);
            var flags = GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
            for (var index = 0; index < READBACK_SLOTS; index++) {
                readback[index] = GL45.glCreateBuffers();
                GL45.glNamedBufferStorage(readback[index], 16, flags);
                readbackData[index] = GL45.glMapNamedBufferRange(readback[index], 0, 16, flags)
                                          .order(ByteOrder.nativeOrder());
            }
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private void defines(Compilation compilation) {
        compilation.define("FLW_REPLAY_COUNTS", Integer.toString(abi.countsBinding()));
        compilation.define("FLW_REPLAY_COMMANDS", Integer.toString(abi.commandsBinding()));
        compilation.define("FLW_REPLAY_WORDS", Integer.toString(commandWords));
        compilation.define("FLW_REPLAY_COMPUTES",
                Integer.toString(computes.size() + (resourceComputeGroupSize == 0 ? 0 : 1)));
    }

    void before(DeferredReplayPass current) {
        finishNearest();
        if (current == after) finish(true);
        if (current == passes[0] && BackendManagerImpl.isBackendOn()) replay();
        if (frameOpen && nearestStage < passes.length && current == passes[nearestStage]) {
            resources.beforePass(current, 0);
            pendingNearest = current;
            nearestStage++;
        }
    }

    void executeNative(DeferredReplayPass pass) {
        before(pass);
        try (var execution = new DeferredReplayExecution(this, pass, 0, false)) {
            pass.execute();
        }
        if (pendingNearest == pass) finishNearest();
    }

    private void finishNearest() {
        if (pendingNearest == null) return;
        var completed = pendingNearest;
        pendingNearest = null;
        resources.afterPass(completed, 0);
    }

    void finish(boolean completed) {
        try {
            if (completed) finishNearest();
            else pendingNearest = null;
        } finally {
            if (frameOpen) {
                frameOpen = false;
                nearestStage = 0;
                for (var program : uniforms.keySet()) setLayer(program, 0);
                replayFlags(false);
                resources.restore();
            }
        }
    }

    private void registerUniforms(int program) {
        if (program == 0) return;
        uniforms.put(program, new LayerUniforms(GL20.glGetUniformLocation(program, "flw_oitFarLayer"),
                GL20.glGetUniformLocation(program, "flw_oitLayerIndex"),
                GL20.glGetUniformLocation(program, "flw_oitReplaying")));
    }

    private void setLayer(int program, int layer) {
        var locations = uniforms.get(program);
        GL45.glProgramUniform1i(program, locations.far, layer > 0 ? 1 : 0);
        GL45.glProgramUniform1i(program, locations.layer, layer);
    }

    private void replayFlags(boolean enabled) {
        for (var pass : passes) {
            if (pass.program() != 0)
                GL45.glProgramUniform1i(pass.program(), uniforms.get(pass.program()).replaying, enabled ? 1 : 0);
            for (var compute : pass.computes())
                GL45.glProgramUniform1i(compute.program(), uniforms.get(compute.program()).replaying, enabled ? 1 : 0);
        }
    }

    private long drawOffset(int layer) {
        return (long) layer * commandWords * 4;
    }

    private void replay() {
        resources.prepareCapture();
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        harvestReadbacks();
        if (readbackFence[readbackSlot] == 0) {
            GL45.glCopyNamedBufferSubData(storage.buffer(abi.countsBinding()), readback[readbackSlot], 0, 0, 16);
            readbackBounds[readbackSlot] = layerBound;
            readbackFence[readbackSlot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        readbackSlot = (readbackSlot + 1) % READBACK_SLOTS;
        if (layerBound == 1) return;
        var previousDraw = GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        var previousDispatch = GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        var previousStorage = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, abi.commandsBinding());
        var completed = false;
        try (var state = GlStateTracker.getRestoreState()) {
            resources.snapshot();
            frameOpen = true;
            replayFlags(true);
            commandsProgram.bind();
            commandsProgram.setUInt("bound", layerBound);
            for (var index = 0; index < computes.size(); index++) {
                var compute = computes.get(index);
                if (compute.indirect()) continue;
                GL45.glProgramUniform3ui(commandsProgram.handle(),
                        commandsProgram.getUniformLocation("workGroups[" + index + "]"),
                        compute.groupsX(), compute.groupsY(), compute.groupsZ());
            }
            if (resourceComputeGroupSize != 0) {
                var x = (resources.width() + resourceComputeGroupSize - 1) / resourceComputeGroupSize;
                var y = (resources.height() + resourceComputeGroupSize - 1) / resourceComputeGroupSize;
                GL45.glProgramUniform3ui(commandsProgram.handle(),
                        commandsProgram.getUniformLocation("workGroups[" + computes.size() + "]"), x, y, 1);
                resources.replayCommands(commands, 20L + computes.size() * 12L);
            }
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.commandsBinding(), commands);
            GL43.glDispatchCompute((layerBound + 63) / 64, 1, 1);
            GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            if (mask != null) mask.prepare(resources.width(), resources.height(), storage.buffer(abi.tilesBinding()));
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commands);
            GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, commands);
            for (var layer = layerBound - 1; layer > 0; layer--) {
                GL11.glViewport(0, 0, resources.width(), resources.height());
                quad.bind();
                resources.materialize(layer, GL11.GL_UNSIGNED_INT, drawOffset(layer));
                if (mask != null) mask.layer(layer, drawOffset(layer), quad);
                for (var pass : passes) {
                    resources.beforePass(pass, layer);
                    try (var execution = new DeferredReplayExecution(this, pass, layer, true)) {
                        pass.execute();
                    } finally {
                        // AUSM releases its native indirect binding; history callbacks still consume replay commands.
                        for (var compute : pass.computes())
                            if (compute.indirect()) {
                                GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, commands);
                                break;
                            }
                    }
                    resources.afterPass(pass, layer);
                }
                GL11.glViewport(0, 0, resources.width(), resources.height());
                quad.bind();
                resources.advance(layer, GL11.GL_UNSIGNED_INT, drawOffset(layer));
            }
            GL11.glViewport(0, 0, resources.width(), resources.height());
            for (var program : uniforms.keySet()) setLayer(program, 0);
            quad.bind();
            resources.materialize(0, GL11.GL_UNSIGNED_INT, drawOffset(0));
            completed = true;
        } finally {
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, previousDraw);
            GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, previousDispatch);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.commandsBinding(), previousStorage);
            if (!completed) finish(false);
        }
    }

    void bindGraphics(DeferredReplayPass pass, int program, int layer) {
        if (program != pass.program()) throw new IllegalStateException("Native replay program changed: " + pass.name());
        GlStateTracker._setProgram(program);
        if (frameOpen) {
            setLayer(program, layer);
            resources.bindGraphicsResources(pass, layer);
        }
    }

    void draw(DeferredReplayPass pass, int layer) {
        quad.bind();
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commands);
        if (mask != null && masked[stage(pass)]) mask.draw(pass, drawOffset(layer));
        else GL40.glDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, drawOffset(layer));
    }

    private int stage(DeferredReplayPass pass) {
        for (var index = 0; index < passes.length; index++) if (passes[index] == pass) return index;
        throw new IllegalStateException("Unregistered replay stage " + pass.name());
    }

    boolean dispatch(DeferredReplayPass pass, int program, int layer, boolean far, int x, int y, int z) {
        return dispatch(pass, program, layer, far, x, y, z, 0, 0);
    }

    boolean dispatchIndirect(DeferredReplayPass pass, int program, int layer, boolean far, int buffer, long offset) {
        return dispatch(pass, program, layer, far, 0, 0, 0, buffer, offset);
    }

    private boolean dispatch(DeferredReplayPass pass, int program, int layer, boolean far, int x, int y, int z,
                             int buffer, long offset) {
        if (!far && !frameOpen) return false;
        var stage = stage(pass);
        for (var index = computeStarts[stage]; index < computeStarts[stage + 1]; index++) {
            var compute = computes.get(index);
            if (compute.program() != program) continue;
            if (compute.indirect() != (buffer != 0))
                throw new IllegalStateException("Native compute dispatch mode changed: " + pass.name());
            if (compute.indirect() && (compute.indirectBuffer() != buffer || compute.indirectOffset() != offset))
                throw new IllegalStateException("Native compute dispatch pointer changed: " + pass.name());
            GlStateTracker._setProgram(program);
            if (frameOpen) {
                setLayer(program, layer);
                resources.bindComputeResources(compute, layer);
            }
            if (!far) return false;
            if (!compute.indirect() && (compute.groupsX() != x || compute.groupsY() != y || compute.groupsZ() != z))
                throw new IllegalStateException("Native replay compute dimensions changed: " + pass.name());
            var command = drawOffset(layer) + 20L + index * 12L;
            if (compute.indirect()) {
                gateIndirect(buffer, offset, command, layer);
                GlProgram.bind(program);
            }
            GL42.glMemoryBarrier(COMPUTE_BARRIER);
            GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, commands);
            GL43.glDispatchComputeIndirect(command);
            GL42.glMemoryBarrier(COMPUTE_BARRIER);
            return true;
        }
        throw new IllegalStateException("Unregistered native replay compute program " + program);
    }

    private void gateIndirect(int buffer, long sourceOffset, long commandOffset, int layer) {
        if (indirectGate == null)
            indirectGate = DeferredReplayShaders.compute("layer_dispatch_gate.comp", this::defines);
        if ((sourceOffset & 3) != 0 || sourceOffset < 0 || sourceOffset > GL45.glGetNamedBufferParameteri64(buffer,
                GL15.GL_BUFFER_SIZE) - 12)
            throw new IllegalArgumentException("Native indirect compute pointer is outside its buffer");
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL45.glCopyNamedBufferSubData(buffer, commands, sourceOffset, commandOffset, 12);
        var previous = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, abi.commandsBinding());
        try {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.commandsBinding(), commands);
            indirectGate.bind();
            indirectGate.setUInt("layer", layer);
            indirectGate.setUInt("offset", (int) (commandOffset / 4));
            GL43.glDispatchCompute(1, 1, 1);
        } finally {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.commandsBinding(), previous);
        }
        GL42.glMemoryBarrier(GL43.GL_COMMAND_BARRIER_BIT);
    }

    private long maxPoolNodes() {
        return Math.min(maxStorageNodes,
                Math.max(abi.minNodes(), (long) resources.width() * resources.height() * abi.maxNodesPerPixel()));
    }

    private void harvestReadbacks() {
        for (var index = 0; index < READBACK_SLOTS; index++) {
            var slot = (readbackSlot + index) % READBACK_SLOTS;
            var fence = readbackFence[slot];
            if (fence == 0) continue;
            var status = GL32.glClientWaitSync(fence, 0, 0);
            if (status == GL32.GL_WAIT_FAILED) throw new IllegalStateException("Deferred capture fence failed");
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) continue;
            GL32.glDeleteSync(fence);
            readbackFence[slot] = 0;
            var data = readbackData[slot];
            var demand = Integer.toUnsignedLong(data.getInt(0));
            var fallback = data.getInt(4) | (data.getInt(8) > readbackBounds[slot] ? 2 : 0);
            if (fallback != lastFallback) {
                if (fallback != 0) FlwBackend.LOGGER.warn(
                        "Deferred transparency retains native ordering: {} flags={}, slots={}, layers={}, bound={}",
                        passes[0].name(), fallback, demand, data.getInt(8), readbackBounds[slot]);
                lastFallback = fallback;
            }
            var granules = (demand + demand / 4 + abi.nodeGranule() - 1) / abi.nodeGranule();
            var wanted = Math.min(maxPoolNodes(), Math.max(abi.minNodes(), granules * abi.nodeGranule()));
            if (wanted > poolNodes || wanted * 2 <= poolNodes && ++poolShrinks >= SHRINK_READBACKS) {
                var buffer = GL45.glCreateBuffers();
                GL45.glNamedBufferStorage(buffer, wanted * abi.nodeBytes(), 0);
                var old = storage.buffer(abi.nodesBinding());
                storage.replace(abi.nodesBinding(), buffer);
                GL15.glDeleteBuffers(old);
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.nodesBinding(), buffer);
                poolNodes = wanted;
                poolShrinks = 0;
                // The capture preceded this allocation decision; a replacement pool has no current-frame nodes.
                GL45.glClearNamedBufferSubData(storage.buffer(abi.countsBinding()), GL30.GL_R32UI, 4, 4,
                        GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, INVALID_CAPTURE);
            } else if (wanted * 2 > poolNodes) poolShrinks = 0;
            var layers = Math.max(1, data.getInt(8));
            resources.observedCapture(data.getInt(8), data.getInt(12));
            var bin = Math.min(MAX_LAYERS, Integer.highestOneBit(layers * 2 - 1));
            if (bin > layerBound) {
                layerBound = bin;
                if (bin > commandLayers) {
                    var buffer = GL45.glCreateBuffers();
                    GL45.glNamedBufferStorage(buffer, bin * commandWords * 4L, 0);
                    GL15.glDeleteBuffers(commands);
                    commands = buffer;
                    commandLayers = bin;
                }
                shrinkReadbacks = 0;
            } else if (bin < layerBound && ++shrinkReadbacks >= SHRINK_READBACKS) {
                layerBound >>= 1;
                shrinkReadbacks = 0;
            } else if (bin == layerBound) shrinkReadbacks = 0;
            if (layerBound > 1) idleReadbacks = 0;
            else if (++idleReadbacks == RELEASE_READBACKS) resources.release();
        }
    }

    @Override
    public void close() {
        try {
            finish(false);
        } finally {
            try {
                resources.close();
            } finally {
                if (quad != null) {
                    quad.delete();
                    quad = null;
                }
                if (mask != null) {
                    mask.close();
                    mask = null;
                }
                if (commandsProgram != null) {
                    commandsProgram.delete();
                    commandsProgram = null;
                }
                if (indirectGate != null) {
                    indirectGate.delete();
                    indirectGate = null;
                }
                GL15.glDeleteBuffers(commands);
                commands = 0;
                for (var index = 0; index < READBACK_SLOTS; index++) {
                    if (readbackFence[index] != 0) GL32.glDeleteSync(readbackFence[index]);
                    if (readbackData[index] != null) GL45.glUnmapNamedBuffer(readback[index]);
                    if (readback[index] != 0) GL15.glDeleteBuffers(readback[index]);
                    readback[index] = 0;
                    readbackFence[index] = 0;
                    readbackData[index] = null;
                }
            }
        }
    }

    private record LayerUniforms(int far, int layer, int replaying) {
    }
}
