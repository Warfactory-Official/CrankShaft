package me.mlbv.meshlet.mesh.gl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.engine.terrain.*;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.buffer.GlResidentBuffer;
import dev.engine_room.flywheel.backend.gl.shader.MeshGlPrograms;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import dev.engine_room.flywheel.iris.compile.GuestProgram;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

final class IrisTerrainRasterizer {
    private static final boolean CULL = !Boolean.getBoolean("crankshaft.iris.mesh.cullOff");
    private static final int SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV = 0x10;
    private static final int OPAQUE_COMMANDS = ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE;
    private static final int TRANSLUCENT_COMMANDS = RenderRegion.REGION_SIZE;
    private final GlResidentAddressCache addresses = new GlResidentAddressCache();
    private final Reference2ObjectOpenHashMap<GpuBuffer, GlResidentBuffer> visibility = new Reference2ObjectOpenHashMap<>();
    private final GlResidentBuffer geometryPointers = new GlResidentBuffer();
    private final GlResidentBuffer runBuffer = new GlResidentBuffer();
    private final GlResidentBuffer compactedCommands = new GlResidentBuffer();
    private final GlBuffer sceneBuffer = new GlBuffer(GlBufferUsage.STREAM_DRAW);
    private final MemoryBlock scene = MemoryBlock.malloc(64);
    private final int maxTaskCount = GL11C.glGetInteger(NVMeshShader.GL_MAX_DRAW_MESH_TASKS_COUNT_NV);
    private MemoryBlock geometry = MemoryBlock.malloc(8);
    private MemoryBlock runMemory = MemoryBlock.malloc(16);
    private int[] runs = new int[4];
    private int runCount;
    private int converter;
    private int converterQuads;
    private int recovery;
    private int recoveryQuads;
    private int recoveryRun;
    private int recoveryDepth;
    private int compactor;
    private boolean runsWritten;

    void draw(TerrainDrawDispatcher dispatcher, int passIndex) {
        boolean oit = passIndex >= 2;
        int count = oit ? dispatcher.translucentRegionBatch.count : dispatcher.boundBatch.count;
        int maxIndices = oit ? dispatcher.translucentRegionBatch.maxIndexCount : dispatcher.boundBatch.maxIndexCount;
        if (count == 0 || maxIndices == 0) return;
        boolean shadow = GuestTerrainGate.ownsShadowTerrain();
        RenderPipeline pipeline = oit ? TerrainPipelines.translucentOit(passIndex - 2)
                : shadow ? TerrainPipelines.shadow(passIndex == 1)
                : passIndex == 0 ? TerrainPipelines.solid() : TerrainPipelines.cutout();
        GuestProgram program = (GuestProgram) GuestPipelines.compiled(pipeline).program();
        if (!oit && !shadow) {
            if (dispatcher.boundPhase == 2 && !program.meshTaskRecovery()) return;
            GuestTerrainGate.meshNeedsRecovery |= program.meshTaskRecovery();
        }
        if ((maxIndices / 6L + program.meshTaskQuads() - 1) / program.meshTaskQuads() > maxTaskCount) {
            throw new IllegalStateException("Terrain command exceeds the device's mesh task-count limit");
        }
        GpuBuffer[] buffers = oit ? dispatcher.translucentRegionBatch.geometryBuffers : dispatcher.boundBatch.geometryBuffers;
        GlResidentBuffer input = oit ? dispatcher.translucentRegionInputBuffer : dispatcher.regionInputBuffers[passIndex];
        GlResidentBuffer commands = oit ? dispatcher.translucentCommandBuffer : dispatcher.commandBuffers[passIndex];
        GlResidentBuffer counts = oit ? dispatcher.translucentCommandCount : dispatcher.regionCommandCounts[passIndex];
        boolean compact = GuestTerrainGate.CACHE_RECOVERY && !shadow && !oit && dispatcher.boundPhase == 2 && program.meshCompactSafe();
        if (compact) compactedCommands.ensureCapacity(commands.capacity());
        uploadRuns(buffers, count, oit, !shadow && !oit && program.meshTaskRecovery());
        long address = scene.ptr();
        MemoryUtil.memPutLong(address, input.deviceAddress());
        MemoryUtil.memPutLong(address + 8, commands.deviceAddress());
        MemoryUtil.memPutLong(address + 16, counts.deviceAddress());
        MemoryUtil.memPutLong(address + 24, geometryPointers.deviceAddress());
        MemoryUtil.memPutLong(address + 32, runBuffer.deviceAddress());
        GuestTerrainGate.meshDepthTexture = dispatcher.guestTaskPyramid(oit);
        int cull = shadow || !CULL ? 0
                : 1 | (pipeline.isCull() ? 2 : 0) | (GuestTerrainGate.meshDepthTexture != 0 ? 4 : 0);
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (GuestTerrainGate.CACHE_RECOVERY && (target.width > 131072 || target.height > 131072)) {
            throw new IllegalStateException("Iris mesh recovery bounds exceed their coordinate range");
        }
        MemoryUtil.memPutInt(address + 40, cull);
        MemoryUtil.memPutInt(address + 44, shadow || oit ? 0 : dispatcher.boundPhase);
        MemoryUtil.memPutInt(address + 48, target.width);
        MemoryUtil.memPutInt(address + 52, target.height);
        MemoryUtil.memPutLong(address + 56, compact ? compactedCommands.deviceAddress() : 0);
        sceneBuffer.upload(scene);
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, 7, sceneBuffer.handle(), 0, 64);
        // Convert once for wavelet depth-range or for the independent single-pass deferred capture.
        if (!oit || passIndex == 2 || passIndex == 5) {
            ensureConverter();
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT
                    | SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
            GlStateTracker.useProgram(converter);
            GL30C.glUniform1ui(converterQuads, program.meshTaskQuads());
            GL43C.glDispatchCompute(runCount, 1, 1);
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT
                    | SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
            if (!shadow && !oit && dispatcher.boundPhase == 2) {
                runsWritten = true;
                ensureRecovery();
                GlStateTracker.useProgram(recovery);
                GL30C.glUniform1ui(recoveryQuads, program.meshTaskQuads());
                if (GuestTerrainGate.CACHE_RECOVERY) {
                    GL20C.glUniform1i(recoveryDepth, Samplers.DEPTH_PYRAMID.number);
                    Samplers.DEPTH_PYRAMID.makeActive();
                    GlStateManager._bindTexture(GuestTerrainGate.meshDepthTexture);
                }
                GlBufferType.DISPATCH_INDIRECT_BUFFER.bind(runBuffer.handle());
                for (int run = 0; run < runCount; run++) {
                    GL30C.glUniform1ui(recoveryRun, run);
                    GL43C.glDispatchComputeIndirect((long) run * 32 + 16);
                }
                GL42C.glMemoryBarrier(GL42C.GL_COMMAND_BARRIER_BIT | SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
                if (compact) {
                    ensureCompactor();
                    GlStateTracker.useProgram(compactor);
                    GL43C.glDispatchCompute(runCount, 1, 1);
                    GL42C.glMemoryBarrier(GL42C.GL_COMMAND_BARRIER_BIT | SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
                    commands = compactedCommands;
                    MemoryUtil.memPutLong(address + 8, commands.deviceAddress());
                    sceneBuffer.upload(scene);
                    GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, 7, sceneBuffer.handle(), 0, 64);
                }
            }
        }

        Minecraft mc = Minecraft.getInstance();
        program.meshCommands(commands.handle(), counts.handle(), runs, runCount);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "flywheel:iris/mesh_terrain", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            dispatcher.bindGuestUniforms(pass, shadow ? ShadowRenderer.MODELVIEW
                    : CapturedRenderingState.INSTANCE.getGbufferModelView());
            pass.bindTexture("Sampler0",
                    mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
                    TerrainAtlasFilter.sampler());
            pass.bindTexture("Sampler2", mc.gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setVertexBuffer(0, buffers[0].slice());
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void uploadRuns(GpuBuffer[] buffers, int count, boolean translucent, boolean historyNeeded) {
        long geometryBytes = (long) count * 16;
        if (geometryBytes > geometry.size()) geometry = geometry.realloc(geometryBytes);
        geometryPointers.ensureCapacity(geometryBytes);
        for (int slot = 0; slot < count; slot++) {
            GpuBuffer arena = buffers[slot];
            long entry = geometry.ptr() + (long) slot * 16;
            MemoryUtil.memPutLong(entry, addresses.address(arena));
            long historyAddress = 0;
            if (historyNeeded) {
                GlResidentBuffer history = visibility.get(arena);
                if (history == null) {
                    history = new GlResidentBuffer();
                    history.ensureCapacity(Math.max(4, arena.size() / TerrainVertexFormat.strideBytes())
                            * (GuestTerrainGate.CACHE_RECOVERY ? 4 : 1));
                    visibility.put(arena, history);
                }
                historyAddress = history.deviceAddress();
            }
            MemoryUtil.memPutLong(entry + 8, historyAddress);
        }
        addresses.finishFill();
        for (var iterator = visibility.reference2ObjectEntrySet().fastIterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.getKey().isClosed()) {
                entry.getValue().delete();
                iterator.remove();
            }
        }
        geometryPointers.uploadSpan(0, geometry.ptr(), geometryBytes);

        int capacity = count * (translucent ? 2 : 1) * 4;
        if (runs.length < capacity) runs = new int[capacity];
        runCount = 0;
        for (int first = 0; first < count; ) {
            int end = first + 1;
            while (end < count && buffers[end] == buffers[first]) end++;
            int length = end - first;
            if (translucent) {
                addRun(first * TRANSLUCENT_COMMANDS * 2, length * TRANSLUCENT_COMMANDS, first * 2);
                addRun(first * TRANSLUCENT_COMMANDS * 2 + length * TRANSLUCENT_COMMANDS,
                        length * TRANSLUCENT_COMMANDS, first * 2 + 1);
            } else {
                addRun(first * OPAQUE_COMMANDS, length * OPAQUE_COMMANDS, first * 2);
            }
            first = end;
        }
        long runBytes = (long) runCount * 32;
        if (runBytes > runMemory.size()) runMemory = runMemory.realloc(runBytes);
        for (int run = 0; run < runCount; run++) {
            MemoryUtil.memIntBuffer(runMemory.ptr() + (long) run * 32, 4).put(runs, run * 4, 4);
        }
        if (runsWritten) {
            // The converter writes dispatch records into this buffer; the next upload replaces those words.
            GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
            runsWritten = false;
        }
        runBuffer.ensureCapacity(runBytes);
        runBuffer.uploadSpan(0, runMemory.ptr(), runBytes);
    }

    private void addRun(int firstCommand, int capacity, int countWord) {
        int offset = runCount++ * 4;
        runs[offset] = firstCommand;
        runs[offset + 1] = capacity;
        runs[offset + 2] = countWord;
        runs[offset + 3] = 0;
    }

    private void ensureConverter() {
        if (converter != 0) return;
        String source = ShaderAssembly.assemble(ctx -> {
            ctx.requireExtension("GL_NV_gpu_shader5");
            ctx.requireExtension("GL_NV_shader_buffer_load");
            ctx.define("_FLW_VERTEX_WORDS", Integer.toString(TerrainVertexFormat.strideBytes() / 4));
            if (GuestTerrainGate.CACHE_RECOVERY) ctx.define("_FLW_CACHE_RECOVERY");
        }, List.of(FlwPrograms.SOURCES.get(Identifier.fromNamespaceAndPath("meshlet", "iris/terrain/convert.comp"))));
        int shader = MeshGlPrograms.compileShader("iris_mesh_shader", GL43C.GL_COMPUTE_SHADER,
                "convert terrain commands", source);
        if (shader == 0) throw new IllegalStateException("Iris mesh command conversion did not compile");
        converter = MeshGlPrograms.linkProgram("iris_mesh_shader", "convert terrain commands", shader);
        if (converter != 0) GL20C.glDetachShader(converter, shader);
        GL20C.glDeleteShader(shader);
        if (converter == 0) throw new IllegalStateException("Iris mesh command conversion did not link");
        converterQuads = GL20C.glGetUniformLocation(converter, "_flw_quadsPerTask");
    }

    void delete() {
        if (converter != 0) GL20C.glDeleteProgram(converter);
        if (recovery != 0) GL20C.glDeleteProgram(recovery);
        if (compactor != 0) GL20C.glDeleteProgram(compactor);
        compactedCommands.delete();
        geometryPointers.delete();
        runBuffer.delete();
        sceneBuffer.delete();
        scene.free();
        geometry.free();
        runMemory.free();
        addresses.clear();
        visibility.values().forEach(GlResidentBuffer::delete);
        visibility.clear();
    }

    private void ensureRecovery() {
        if (recovery != 0) return;
        String source = ShaderAssembly.assemble(ctx -> {
            ctx.requireExtension("GL_NV_gpu_shader5");
            ctx.requireExtension("GL_NV_shader_buffer_load");
            ctx.requireExtension("GL_KHR_shader_subgroup_basic");
            ctx.requireExtension("GL_KHR_shader_subgroup_arithmetic");
            ctx.define("_FLW_VERTEX_WORDS", Integer.toString(TerrainVertexFormat.strideBytes() / 4));
            if (GuestTerrainGate.CACHE_RECOVERY) ctx.define("_FLW_CACHE_RECOVERY");
        }, List.of(FlwPrograms.SOURCES.get(Identifier.fromNamespaceAndPath("meshlet", GuestTerrainGate.CACHE_RECOVERY
                ? "iris/terrain/recovery_cached.comp" : "iris/terrain/recovery.comp"))));
        int shader = MeshGlPrograms.compileShader("iris_mesh_shader", GL43C.GL_COMPUTE_SHADER,
                "filter terrain recovery", source);
        if (shader == 0) throw new IllegalStateException("Iris mesh recovery filtering did not compile");
        recovery = MeshGlPrograms.linkProgram("iris_mesh_shader", "filter terrain recovery", shader);
        if (recovery != 0) GL20C.glDetachShader(recovery, shader);
        GL20C.glDeleteShader(shader);
        if (recovery == 0) throw new IllegalStateException("Iris mesh recovery filtering did not link");
        recoveryQuads = GL20C.glGetUniformLocation(recovery, "_flw_quadsPerTask");
        recoveryRun = GL20C.glGetUniformLocation(recovery, "_flw_runIndex");
        recoveryDepth = GL20C.glGetUniformLocation(recovery, "_flw_taskDepth");
    }

    private void ensureCompactor() {
        if (compactor != 0) return;
        String source = ShaderAssembly.assemble(ctx -> {
            ctx.requireExtension("GL_NV_gpu_shader5");
            ctx.requireExtension("GL_NV_shader_buffer_load");
            ctx.requireExtension("GL_KHR_shader_subgroup_basic");
            ctx.requireExtension("GL_KHR_shader_subgroup_arithmetic");
            ctx.define("_FLW_VERTEX_WORDS", Integer.toString(TerrainVertexFormat.strideBytes() / 4));
            if (GuestTerrainGate.CACHE_RECOVERY) ctx.define("_FLW_CACHE_RECOVERY");
        }, List.of(FlwPrograms.SOURCES.get(Identifier.fromNamespaceAndPath("meshlet", "iris/terrain/compact.comp"))));
        int shader = MeshGlPrograms.compileShader("iris_mesh_shader", GL43C.GL_COMPUTE_SHADER,
                "compact terrain recovery", source);
        if (shader == 0) throw new IllegalStateException("Iris mesh recovery compaction did not compile");
        compactor = MeshGlPrograms.linkProgram("iris_mesh_shader", "compact terrain recovery", shader);
        if (compactor != 0) GL20C.glDetachShader(compactor, shader);
        GL20C.glDeleteShader(shader);
        if (compactor == 0) throw new IllegalStateException("Iris mesh recovery compaction did not link");
    }
}
