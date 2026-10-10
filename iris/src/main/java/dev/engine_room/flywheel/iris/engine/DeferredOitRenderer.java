package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.iris.mixin.ComputeProgramAccessor;
import dev.engine_room.flywheel.iris.mixin.ShaderStorageBufferAccessor;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Pack-independent deferred layer scheduler. A plan is resolved once against an Iris CompositeRenderer; its
 * resource integration owns material encoding and temporal state. Render-thread-only resources are released
 * with that renderer. GPU-generated draw/dispatch commands suppress inactive layers without synchronous readback.
 * Fenced counters resize the node pool and loop bound; overflow retains the integration's native frame.
 */
public final class DeferredOitRenderer implements AutoCloseable {
    private static final int MAX_LAYERS = 64;
    private static final int READBACK_SLOTS = 3;
    private static final int SHRINK_READBACKS = 60;
    private static final int RELEASE_READBACKS = 600;
    private static final int COMPUTE_BARRIER = GL43C.GL_SHADER_STORAGE_BARRIER_BIT
            | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT;
    private static final int[] ZERO_STENCIL = {0};
    private static final int[] INVALID_CAPTURE = {1};
    private final DeferredReplayResources resources;
    private final DeferredCompositePass first;
    private final @Nullable DeferredCompositePass after;
    private final DeferredCompositePass[] passes;
    private final Program[] graphics;
    private final boolean[] masked;
    private final int[] farLocations;
    private final int[] layerLocations;
    private final int[] replayLocations;
    private final RenderTarget[][] mipTargets;
    private final boolean[][] mipAlt;
    private final ComputeProgram[] computes;
    private final int[] computeStarts;
    private final int[] computeFarLocations;
    private final int[] computeLayerLocations;
    private final int[] computeReplayLocations;
    private final int[] workGroups;
    private final int resourceComputeGroupSize;
    private final boolean concurrentCompute;
    private final CustomUniforms uniforms;
    private final ShaderStorageBuffer nodes;
    private final ShaderStorageBuffer counts;
    private final int countsBinding;
    private final int tilesBinding;
    private final int commandsBinding;
    private final int nodeBytes;
    private final int minNodes;
    private final int nodeGranule;
    private final int maxNodesPerPixel;
    private final long maxStorageNodes;
    private final int tileShift;
    private final int tileRadius;
    private final int commandWords;
    private final int[] readback = new int[READBACK_SLOTS];
    private final ByteBuffer[] readbackData = new ByteBuffer[READBACK_SLOTS];
    private final long[] readbackFence = new long[READBACK_SLOTS];
    private final int[] readbackBounds = new int[READBACK_SLOTS];
    private final boolean usesStencil;
    private final boolean preservesViewport;
    private int readbackSlot;
    private int layerBound = 1;
    private int commandLayers = 1;
    private int shrinkReadbacks;
    private int idleReadbacks;
    private long poolNodes;
    private int lastFallback;
    private int poolShrinks;
    private int commands;
    private int commandsProgram;
    private int boundLocation;
    private int groupsLocation;
    private int tilesProgram;
    private int tilesSizeLocation;
    private int maskProgram;
    private int maskSizeLocation;
    private int maskLayerLocation;
    private int indirectGate;
    private int gateLayerLocation;
    private int gateOffsetLocation;
    private int stencil;
    private int maskFbo;
    private int width;
    private int height;
    private int tilesX;
    private int tilesY;
    private int computeWidth;
    private int computeHeight;
    private int resourceComputeWidth;
    private int resourceComputeHeight;
    private boolean frameOpen;
    private boolean maskAttached;
    private boolean stencilEnabled;
    private boolean viewportKnown;
    private int viewportX, viewportY, viewportWidth, viewportHeight;
    private int nearestStage;
    private @Nullable DeferredCompositePass pendingNearest;

    private DeferredOitRenderer(DeferredReplayPlan plan, RenderTargets targets, CustomUniforms uniforms,
                                ShaderStorageBuffer[] storage, boolean concurrentCompute) {
        resources = plan.resources();
        preservesViewport = resources.preservesViewport();
        this.uniforms = uniforms;
        this.concurrentCompute = concurrentCompute;
        var abi = plan.storage();
        nodes = storage[abi.nodesBinding()];
        counts = storage[abi.countsBinding()];
        countsBinding = abi.countsBinding();
        tilesBinding = abi.tilesBinding();
        commandsBinding = abi.commandsBinding();
        nodeBytes = abi.nodeBytes();
        maxStorageNodes = GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) / nodeBytes;
        minNodes = abi.minNodes();
        nodeGranule = abi.nodeGranule();
        maxNodesPerPixel = abi.maxNodesPerPixel();
        tileShift = abi.tileShift();
        tileRadius = plan.tileRadius();
        poolNodes = GL45C.glGetNamedBufferParameteri64(nodes.getId(), GL15C.GL_BUFFER_SIZE) / nodeBytes;
        var stages = plan.stages();
        first = stages[0].pass();
        after = plan.after();
        passes = new DeferredCompositePass[stages.length];
        graphics = new Program[stages.length];
        masked = new boolean[stages.length];
        farLocations = new int[stages.length];
        layerLocations = new int[stages.length];
        replayLocations = new int[stages.length];
        mipTargets = new RenderTarget[stages.length][];
        mipAlt = new boolean[stages.length][];
        computeStarts = new int[stages.length + 1];
        var computeList = new ArrayList<ComputeProgram>();
        boolean mask = false;
        for (int i = 0; i < stages.length; i++) {
            DeferredCompositePass pass = passes[i] = stages[i].pass();
            Program program = graphics[i] = pass.flywheel$program();
            computeStarts[i] = computeList.size();
            for (ComputeProgram compute : pass.flywheel$computes()) if (compute != null) computeList.add(compute);
            if (program == null) continue;
            farLocations[i] = GL20C.glGetUniformLocation(program.getProgramId(), "flw_oitFarLayer");
            layerLocations[i] = GL20C.glGetUniformLocation(program.getProgramId(), "flw_oitLayerIndex");
            replayLocations[i] = GL20C.glGetUniformLocation(program.getProgramId(), "flw_oitReplaying");
            masked[i] = stages[i].tileMasked();
            mask |= masked[i];
            var view = pass.flywheel$viewportScale();
            if (masked[i] && (view.scale() != 1 || view.viewportX() != 0 || view.viewportY() != 0)) {
                throw new IllegalArgumentException("Tile-masked replay needs a full viewport: " + pass.flywheel$name());
            }
            int[] buffers = pass.flywheel$mipmaps().stream().mapToInt(Integer::intValue).toArray();
            mipTargets[i] = new RenderTarget[buffers.length];
            mipAlt[i] = new boolean[buffers.length];
            for (int j = 0; j < buffers.length; j++) {
                mipTargets[i][j] = targets.get(buffers[j]);
                mipAlt[i][j] = pass.flywheel$readAlt().contains(buffers[j]);
            }
        }
        usesStencil = mask;
        computeStarts[stages.length] = computeList.size();
        computes = computeList.toArray(ComputeProgram[]::new);
        computeFarLocations = new int[computes.length];
        computeLayerLocations = new int[computes.length];
        computeReplayLocations = new int[computes.length];
        resourceComputeGroupSize = resources.computeGroupSize();
        if (resourceComputeGroupSize < 0) throw new IllegalArgumentException("Invalid resource compute group size");
        workGroups = new int[(computes.length + (resourceComputeGroupSize == 0 ? 0 : 1)) * 3];
        for (int i = 0; i < computes.length; i++) {
            computeFarLocations[i] = GL20C.glGetUniformLocation(computes[i].getProgramId(), "flw_oitFarLayer");
            computeLayerLocations[i] = GL20C.glGetUniformLocation(computes[i].getProgramId(), "flw_oitLayerIndex");
            computeReplayLocations[i] = GL20C.glGetUniformLocation(computes[i].getProgramId(), "flw_oitReplaying");
        }
        commandWords = 5 + workGroups.length;
        try {
            commandsProgram = compute(kernel("layer_commands.comp"));
            boundLocation = GL20C.glGetUniformLocation(commandsProgram, "bound");
            groupsLocation = GL20C.glGetUniformLocation(commandsProgram, "workGroups");
            if (usesStencil) {
                tilesProgram = compute(kernel("layer_tiles.comp"));
                maskProgram = DeferredReplayShaders.link(new int[]{GL20C.GL_VERTEX_SHADER, GL20C.GL_FRAGMENT_SHADER},
                        DeferredReplayShaders.source("layer_fullscreen.vert", ShaderAssembly.NO_EXTRA),
                        kernel("layer_mask.frag"));
                tilesSizeLocation = GL20C.glGetUniformLocation(tilesProgram, "tileCount");
                maskSizeLocation = GL20C.glGetUniformLocation(maskProgram, "tileCount");
                maskLayerLocation = GL20C.glGetUniformLocation(maskProgram, "layerIndex");
            }
            commands = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(commands, commandWords * 4L, 0);
            int flags = GL30C.GL_MAP_READ_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT;
            for (int i = 0; i < READBACK_SLOTS; i++) {
                readback[i] = GL45C.glCreateBuffers();
                GL45C.glNamedBufferStorage(readback[i], 16, flags);
                readbackData[i] = GL45C.glMapNamedBufferRange(readback[i], 0, 16, flags);
            }
        } catch (RuntimeException | Error e) {
            closeCore();
            throw e;
        }
    }

    public static DeferredOitRenderer create(DeferredReplayPlan plan, List<?> passes, RenderTargets targets,
                                             CustomUniforms uniforms,
                                             ShaderStorageBuffer[] storage, boolean concurrentCompute) {
        try {
            plan.validatePasses(passes);
            return new DeferredOitRenderer(plan, targets, uniforms, storage, concurrentCompute);
        } catch (RuntimeException | Error e) {
            try {
                plan.resources().close();
            } catch (RuntimeException | Error cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static int compute(String source) {
        return DeferredReplayShaders.compute(source);
    }

    private static void setLayer(int program, int far, int index, int layer) {
        if (far >= 0) GL45C.glProgramUniform1i(program, far, layer == 0 ? 0 : 1);
        if (index >= 0) GL45C.glProgramUniform1i(program, index, layer);
    }

    private String kernel(String name) {
        return DeferredReplayShaders.source(name, this::defines);
    }

    private void defines(Compilation compilation) {
        compilation.define("FLW_REPLAY_COUNTS", Integer.toString(countsBinding));
        compilation.define("FLW_REPLAY_TILES", Integer.toString(tilesBinding));
        compilation.define("FLW_REPLAY_COMMANDS", Integer.toString(commandsBinding));
        compilation.define("FLW_REPLAY_COMPUTES", Integer.toString(workGroups.length / 3));
        compilation.define("FLW_REPLAY_WORDS", Integer.toString(commandWords));
        compilation.define("FLW_REPLAY_TILE_SHIFT", Integer.toString(tileShift));
        compilation.define("FLW_REPLAY_TILE_RADIUS", Integer.toString(tileRadius));
    }

    public void before(DeferredCompositePass current) {
        finishNearestPass();
        if (current == after) finish();
        if (current == first && BackendManagerImpl.isBackendOn()) {
            GlCompat.pushDebugGroup("flywheel:iris/deferred_layers");
            try {
                replay();
            } finally {
                GlCompat.popDebugGroup();
            }
        }
        if (frameOpen && nearestStage < passes.length && current == passes[nearestStage]) {
            resources.beforePass(current, 0);
            pendingNearest = current;
            nearestStage++;
        }
    }

    private void finishNearestPass() {
        if (pendingNearest != null) {
            DeferredCompositePass completed = pendingNearest;
            pendingNearest = null;
            resources.afterPass(completed, 0);
        }
    }

    public void finish() {
        finish(true);
    }

    public void finish(boolean completed) {
        try {
            if (completed) finishNearestPass();
            else pendingNearest = null;
        } finally {
            if (frameOpen) {
                frameOpen = false;
                nearestStage = 0;
                replayFlags(false);
                resources.restore();
            }
        }
    }

    public void bindNativeResources(Object program) {
        if (pendingNearest == null) return;
        if (program instanceof ComputeProgram compute) resources.bindComputeResources(compute, 0);
        else resources.bindGraphicsResources(pendingNearest, 0);
    }

    private void replay() {
        resources.prepareCapture();
        // Publish capture to both shader consumers and API copies/clears made while harvesting demand.
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
        harvestReadbacks();
        if (readbackFence[readbackSlot] == 0) {
            GL45C.glCopyNamedBufferSubData(counts.getId(), readback[readbackSlot], 0, 0, 16);
            readbackBounds[readbackSlot] = layerBound;
            readbackFence[readbackSlot] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        readbackSlot = (readbackSlot + 1) % READBACK_SLOTS;
        if (layerBound == 1) return;
        int w = resources.width(), h = resources.height();
        if (w != width || h != height) {
            releaseMask();
            width = w;
            height = h;
            if (usesStencil) allocateMask();
        }
        boolean depthEnabled = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean depthWrite = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        int previousDraw = GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
        int previousDispatch = workGroups.length == 0 ? 0 : GL11C.glGetInteger(
                GL43C.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        boolean completed = false;
        viewportKnown = false;
        try {
            resources.snapshot();
            frameOpen = true;
            replayFlags(true);
            var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ((GlBuffer) indices.getBuffer(6)).handle());
            int indexType = GlConst.toGl(indices.type());
            updateComputeGroups();
            int previousStorage = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, commandsBinding);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, commandsBinding, commands);
            GlStateManager._glUseProgram(commandsProgram);
            GL45C.glProgramUniform1ui(commandsProgram, boundLocation, layerBound);
            GL43C.glDispatchCompute((layerBound + 63) / 64, 1, 1);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, commandsBinding, previousStorage);
            if (usesStencil) {
                GlStateManager._glUseProgram(tilesProgram);
                GL43C.glDispatchCompute((tilesX + 7) >> 3, (tilesY + 7) >> 3, 1);
                maskAttached = true;
                for (int i = 0; i < passes.length; i++)
                    if (masked[i]) {
                        if (passes[i].flywheel$viewWidth() != width || passes[i].flywheel$viewHeight() != height)
                            throw new IllegalStateException(
                                    "Tile-masked pass dimensions changed: " + passes[i].flywheel$name());
                        GL45C.glNamedFramebufferRenderbuffer(passes[i].flywheel$framebuffer().getId(),
                                GL30C.GL_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, stencil);
                    }
                GL45C.glClearNamedFramebufferiv(maskFbo, GL11C.GL_STENCIL, 0, ZERO_STENCIL);
            }
            GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, commands);
            if (resourceComputeGroupSize != 0) {
                GlStateManager._glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, commands);
                resources.replayCommands(commands, 20L + computes.length * 12L);
            }
            for (int layer = layerBound - 1; layer > 0; --layer) {
                viewport();
                resources.materialize(layer, indexType, drawOffset(layer));
                if (!preservesViewport) viewportKnown = false;
                if (usesStencil) mask(layer, indexType);
                for (int i = 0; i < passes.length; i++) replayPass(i, layer, indexType);
                viewport();
                if (usesStencil) stencil(true);
                resources.advance(layer, indexType, drawOffset(layer));
                if (!preservesViewport) viewportKnown = false;
            }
            unmask();
            resetLayerUniforms();
            viewport();
            resources.materialize(0, indexType, drawOffset(0));
            completed = true;
        } finally {
            unmask();
            if (!completed) {
                resetLayerUniforms();
                replayFlags(false);
                resources.restore();
                frameOpen = false;
            }
            if (depthEnabled) GlStateManager._enableDepthTest();
            else GlStateManager._disableDepthTest();
            GlStateManager._depthFunc(depthFunc);
            GlStateManager._depthMask(depthWrite);
            GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, previousDraw);
            if (workGroups.length != 0)
                GlStateManager._glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, previousDispatch);
        }
    }

    private void updateComputeGroups() {
        if (workGroups.length == 0) return;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (computeWidth == target.width && computeHeight == target.height
                && (resourceComputeGroupSize == 0 || (resourceComputeWidth == width && resourceComputeHeight == height)))
            return;
        computeWidth = target.width;
        computeHeight = target.height;
        for (int i = 0; i < computes.length; i++) {
            if (((ComputeProgramAccessor) (Object) computes[i]).flywheel$indirectPointer() != null) continue;
            var groups = computes[i].getWorkGroups(computeWidth, computeHeight);
            workGroups[i * 3] = groups.x;
            workGroups[i * 3 + 1] = groups.y;
            workGroups[i * 3 + 2] = groups.z;
        }
        if (resourceComputeGroupSize != 0) {
            resourceComputeWidth = width;
            resourceComputeHeight = height;
            int start = computes.length * 3;
            workGroups[start] = (width + resourceComputeGroupSize - 1) / resourceComputeGroupSize;
            workGroups[start + 1] = (height + resourceComputeGroupSize - 1) / resourceComputeGroupSize;
            workGroups[start + 2] = 1;
        }
        GL45C.glProgramUniform3uiv(commandsProgram, groupsLocation, workGroups);
    }

    private void replayPass(int index, int layer, int indexType) {
        DeferredCompositePass pass = passes[index];
        resources.beforePass(pass, layer);
        if (!preservesViewport) viewportKnown = false;
        for (int i = computeStarts[index]; i < computeStarts[index + 1]; i++) {
            ComputeProgram compute = computes[i];
            long offset = drawOffset(layer) + 20L + i * 12L;
            FilledIndirectPointer indirect = ((ComputeProgramAccessor) (Object) compute).flywheel$indirectPointer();
            if (indirect != null) gateIndirect(indirect, offset, layer);
            compute.use();
            uniforms.push(compute);
            setLayer(compute.getProgramId(), computeFarLocations[i], computeLayerLocations[i], layer);
            resources.bindComputeResources(compute, layer);
            if (!preservesViewport) viewportKnown = false;
            if (!concurrentCompute) IrisRenderSystem.memoryBarrier(COMPUTE_BARRIER);
            GlStateManager._glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, commands);
            IrisRenderSystem.dispatchComputeIndirect(offset);
        }
        if (computeStarts[index] != computeStarts[index + 1]) {
            IrisRenderSystem.memoryBarrier(COMPUTE_BARRIER);
            Program.unbind();
        }
        Program program = graphics[index];
        if (program != null) {
            stencil(masked[index]);
            for (int i = 0; i < mipTargets[index].length; i++) {
                RenderTarget target = mipTargets[index][i];
                boolean alt = mipAlt[index][i];
                IrisRenderSystem.generateMipmaps(alt ? target.getAltTexture() : target.getMainTexture(),
                        GL11C.GL_TEXTURE_2D);
                target.turnOnMips(alt);
            }
            pass.setupState();
            var view = pass.flywheel$viewportScale();
            viewport((int) (pass.flywheel$viewWidth() * view.viewportX()),
                    (int) (pass.flywheel$viewHeight() * view.viewportY()),
                    (int) (pass.flywheel$viewWidth() * view.scale()),
                    (int) (pass.flywheel$viewHeight() * view.scale()));
            GlStateManager._disableScissorTest();
            program.use();
            uniforms.push(program);
            setLayer(program.getProgramId(), farLocations[index], layerLocations[index], layer);
            resources.bindGraphicsResources(pass, layer);
            GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, indexType, drawOffset(layer));
            BlendModeOverride.restore();
        }
        resources.afterPass(pass, layer);
        if (!preservesViewport) viewportKnown = false;
    }

    private void gateIndirect(FilledIndirectPointer pointer, long offset, int layer) {
        if (indirectGate == 0) {
            indirectGate = compute(kernel("layer_dispatch_gate.comp"));
            gateLayerLocation = GL20C.glGetUniformLocation(indirectGate, "layer");
            gateOffsetLocation = GL20C.glGetUniformLocation(indirectGate, "offset");
        }
        GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL45C.glCopyNamedBufferSubData(pointer.buffer(), commands, pointer.offset(), offset, 12);
        int previous = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, commandsBinding);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, commandsBinding, commands);
        GlStateManager._glUseProgram(indirectGate);
        GL45C.glProgramUniform1ui(indirectGate, gateLayerLocation, layer);
        GL45C.glProgramUniform1ui(indirectGate, gateOffsetLocation, (int) (offset / 4));
        GL43C.glDispatchCompute(1, 1, 1);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, commandsBinding, previous);
        GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT);
    }

    private void viewport() {
        viewport(0, 0, width, height);
        GlStateManager._disableScissorTest();
    }

    private void viewport(int x, int y, int w, int h) {
        if (!viewportKnown || x != viewportX || y != viewportY || w != viewportWidth || h != viewportHeight) {
            GlStateManager._viewport(x, y, w, h);
            viewportX = x;
            viewportY = y;
            viewportWidth = w;
            viewportHeight = h;
            viewportKnown = true;
        }
    }

    private void stencil(boolean enabled) {
        if (stencilEnabled == enabled) return;
        if (enabled) GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        else GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        stencilEnabled = enabled;
    }

    private long drawOffset(int layer) {
        return (long) layer * commandWords * 4;
    }

    private void resetLayerUniforms() {
        for (int i = 0; i < graphics.length; i++)
            if (graphics[i] != null)
                setLayer(graphics[i].getProgramId(), farLocations[i], layerLocations[i], 0);
        for (int i = 0; i < computes.length; i++)
            setLayer(computes[i].getProgramId(), computeFarLocations[i], computeLayerLocations[i], 0);
    }

    private void replayFlags(boolean enabled) {
        for (int i = 0; i < graphics.length; i++)
            if (graphics[i] != null && replayLocations[i] >= 0)
                GL45C.glProgramUniform1i(graphics[i].getProgramId(), replayLocations[i], enabled ? 1 : 0);
        for (int i = 0; i < computes.length; i++)
            if (computeReplayLocations[i] >= 0)
                GL45C.glProgramUniform1i(computes[i].getProgramId(), computeReplayLocations[i], enabled ? 1 : 0);
    }

    private void allocateMask() {
        int tileSize = 1 << tileShift;
        tilesX = (width + tileSize - 1) >> tileShift;
        tilesY = (height + tileSize - 1) >> tileShift;
        GL45C.glProgramUniform2i(tilesProgram, tilesSizeLocation, tilesX, tilesY);
        GL45C.glProgramUniform2i(maskProgram, maskSizeLocation, tilesX, tilesY);
        stencil = GL45C.glCreateRenderbuffers();
        GL45C.glNamedRenderbufferStorage(stencil, GL30C.GL_STENCIL_INDEX8, width, height);
        maskFbo = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferRenderbuffer(maskFbo, GL30C.GL_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, stencil);
        GL45C.glNamedFramebufferDrawBuffer(maskFbo, GL11C.GL_NONE);
        if (GL45C.glCheckNamedFramebufferStatus(maskFbo, GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete deferred replay tile mask");
    }

    private void mask(int layer, int indexType) {
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, maskFbo);
        stencil(true);
        GL11C.glStencilFunc(GL11C.GL_ALWAYS, 1, 0xFF);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
        GlStateManager._glUseProgram(maskProgram);
        GL45C.glProgramUniform1i(maskProgram, maskLayerLocation, layer);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, indexType, drawOffset(layer));
        GL11C.glStencilFunc(GL11C.GL_EQUAL, 1, 0xFF);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
    }

    private void unmask() {
        if (!maskAttached) return;
        maskAttached = false;
        stencil(false);
        GL11C.glStencilFunc(GL11C.GL_ALWAYS, 0, 0xFF);
        for (int i = 0; i < passes.length; i++)
            if (masked[i])
                GL45C.glNamedFramebufferRenderbuffer(passes[i].flywheel$framebuffer().getId(),
                        GL30C.GL_STENCIL_ATTACHMENT,
                        GL30C.GL_RENDERBUFFER, 0);
    }

    private long maxPoolNodes(int width, int height) {
        return Math.min(maxStorageNodes, (long) maxNodesPerPixel * width * height);
    }

    private void harvestReadbacks() {
        for (int i = 0; i < READBACK_SLOTS; i++) {
            int slot = (readbackSlot + i) % READBACK_SLOTS;
            long fence = readbackFence[slot];
            if (fence == 0) continue;
            int status = GL32C.glClientWaitSync(fence, 0, 0);
            if (status != GL32C.GL_ALREADY_SIGNALED && status != GL32C.GL_CONDITION_SATISFIED) continue;
            GL32C.glDeleteSync(fence);
            readbackFence[slot] = 0;
            ByteBuffer data = readbackData[slot];
            long demand = Integer.toUnsignedLong(data.getInt(0));
            int fallback = data.getInt(4);
            if (data.getInt(8) > readbackBounds[slot]) fallback |= 2;
            if (fallback != lastFallback) {
                if (fallback != 0) FlwBackend.LOGGER.warn(
                        "Deferred transparency uses native ordering: {} flags={}, requestedSlots={}, layers={}, priorBound={}; pool limit={} slots",
                        first.flywheel$name(), fallback, demand, data.getInt(8), readbackBounds[slot],
                        maxPoolNodes(resources.width(), resources.height()));
                else FlwBackend.LOGGER.info("Deferred transparency sorting restored: {}", first.flywheel$name());
                lastFallback = fallback;
            }
            long granules = (demand + demand / 4 + nodeGranule - 1) / nodeGranule;
            long want = Math.min(maxPoolNodes(resources.width(), resources.height()),
                    Math.max(minNodes, granules * nodeGranule));
            if (want > poolNodes) {
                resizePool(want);
                poolShrinks = 0;
            } else if (want * 2 > poolNodes) poolShrinks = 0;
            else if (++poolShrinks >= SHRINK_READBACKS) {
                resizePool(want);
                poolShrinks = 0;
            }
            int layers = Math.max(1, data.getInt(8));
            resources.observedCapture(data.getInt(8), data.getInt(12));
            int bin = Math.min(MAX_LAYERS, Integer.highestOneBit(layers * 2 - 1));
            if (bin > layerBound) {
                layerBound = bin;
                if (bin > commandLayers) {
                    int buffer = GL45C.glCreateBuffers();
                    GL45C.glNamedBufferStorage(buffer, bin * commandWords * 4L, 0);
                    GlStateManager._glDeleteBuffers(commands);
                    commands = buffer;
                    commandLayers = bin;
                }
                shrinkReadbacks = 0;
            } else if (bin < layerBound && ++shrinkReadbacks >= SHRINK_READBACKS) {
                layerBound >>= 1;
                shrinkReadbacks = 0;
            } else if (bin == layerBound) shrinkReadbacks = 0;
            if (layerBound > 1) idleReadbacks = 0;
            else if (++idleReadbacks == RELEASE_READBACKS) {
                resources.release();
                releaseMask();
            }
        }
    }

    private void resizePool(long count) {
        int buffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(buffer, count * nodeBytes, 0);
        int old = nodes.getId();
        ((ShaderStorageBufferAccessor) nodes).flywheel$setId(buffer);
        GlStateManager._glDeleteBuffers(old);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, nodes.getIndex(), buffer);
        poolNodes = count;
        // Capture ran before the asynchronous resize decision; the replacement has no nodes for this frame.
        GL45C.glClearNamedBufferSubData(counts.getId(), GL30C.GL_R32UI, 4, 4,
                GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, INVALID_CAPTURE);
    }

    /**
     * Live renderer-owned allocations, excluding the Iris-owned node pool. Render thread only.
     */
    public long allocatedBytes() {
        return resources.allocatedBytes() + (stencil == 0 ? 0 : (long) width * height)
                + (commands == 0 ? 0 : commandLayers * commandWords * 4L + READBACK_SLOTS * 16L);
    }

    private void releaseMask() {
        if (maskFbo != 0) GlStateManager._glDeleteFramebuffers(maskFbo);
        if (stencil != 0) GL30C.glDeleteRenderbuffers(stencil);
        maskFbo = stencil = width = height = 0;
    }

    @Override
    public void close() {
        try {
            finish(false);
        } finally {
            try {
                resources.close();
            } finally {
                closeCore();
            }
        }
    }

    private void closeCore() {
        releaseMask();
        if (commandsProgram != 0) GlStateManager.glDeleteProgram(commandsProgram);
        if (tilesProgram != 0) GlStateManager.glDeleteProgram(tilesProgram);
        if (maskProgram != 0) GlStateManager.glDeleteProgram(maskProgram);
        if (indirectGate != 0) GlStateManager.glDeleteProgram(indirectGate);
        if (commands != 0) GlStateManager._glDeleteBuffers(commands);
        for (int i = 0; i < READBACK_SLOTS; i++) {
            if (readbackFence[i] != 0) GL32C.glDeleteSync(readbackFence[i]);
            if (readback[i] != 0) GlStateManager._glDeleteBuffers(readback[i]);
            readbackFence[i] = 0;
            readback[i] = 0;
        }
        commandsProgram = tilesProgram = maskProgram = indirectGate = commands = 0;
    }
}
