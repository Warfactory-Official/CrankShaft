package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDispatcher;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainRegionInput;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionRegistry;
import dev.engine_room.flywheel.impl.mixin.sodium.ChunkRenderListAccessor;
import dev.engine_room.flywheel.iris.compile.ExperimentalOit;
import dev.engine_room.flywheel.iris.compile.ExperimentalPipelines;
import dev.engine_room.flywheel.iris.compile.OitPass;
import dev.engine_room.flywheel.iris.experimental.IndirectCommands;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.backend.WorldPassCommands;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native-list terrain with resident metadata and GPU-built Iris commands.
 */
public final class ExperimentalTerrainDispatcher implements TerrainDispatcher, SodiumTerrainOitReplay {
    private static final int COMMANDS_PER_REGION = 256 * 7;
    private static final long COMMAND_BYTES = COMMANDS_PER_REGION * 20L;
    private final ExperimentalTerrainBuffers resident = new ExperimentalTerrainBuffers();
    private final TerrainSectionRegistry registry = new TerrainSectionRegistry(resident);
    private final SharedQuadIndexBuffer indices = new SharedQuadIndexBuffer(SharedQuadIndexBuffer.IndexFormat.INTEGER);
    private final List<RenderRegion> regions = new ArrayList<>();
    private final ComputePipeline cull;
    private final boolean capturesTerrain;
    private boolean translucentCommandsReady;
    private boolean captured;
    private boolean hasTranslucent;
    private GpuBuffer regionBuffer;
    private GpuBuffer visibility;
    private GpuBuffer times;
    private GpuBuffer commands;
    private GpuBuffer counts;
    private ByteBuffer countData = ByteBuffer.allocateDirect(256);
    private Map<String, List<GpuBufferSlice>> cullBuffers = Map.of();
    private ByteBuffer regionData = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private ByteBuffer masks = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private ByteBuffer clocks = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());

    public ExperimentalTerrainDispatcher() {
        try {
            String source = ShaderAssembly.assemble(_ -> {
            }, List.of(
                    FlwPrograms.SOURCES.get(ResourceUtil.rl("iris/guest_terrain_cull.comp"))));
            source = NativeShaderPreprocessor.preprocessKind(source, "flywheel_terrain_cull",
                    Shaderc.shaderc_compute_shader, Map.of());
            var sources = Map.of(PatchShaderType.COMPUTE, source);
            var layout = VulkanShaderPatcher.discover(sources).layout();
            var parent = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
            var resolver = ((IrisRenderingPipelineAccessor) parent).flywheel$resolver();
            var programs = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs();
            var deferred = programs.flywheel$deferredOit();
            var wavelet = ExperimentalOit.get(parent, false);
            capturesTerrain = deferred != null && deferred.capturesTerrain() || wavelet != null;
            var uniforms = new BufferUniformHolder(layout);
            var resources = new ProgramResources(parent.getPackSamplers(), layout);
            resources.flywheel$externalStorage(Set.of(
                    "_FlwTerrainRegions", "_FlwTerrainSections", "_FlwTerrainVisibility", "_FlwTerrainCommands",
                    "_FlwTerrainCounts"));
            resources.setUniformBuffers(() -> cullBuffers);
            resources.finish();
            cull = Iris.getRenderBackend().createComputePipeline("flywheel_terrain_cull",
                    TransformPatcher.patchProgram("flywheel_terrain_cull", sources, resources, uniforms, null),
                    resources, uniforms);
        } catch (RuntimeException failure) {
            // The terrain owner catches initialization failures and keeps Sodium drawing.
            registry.delete();
            indices.delete();
            throw failure;
        }
    }

    private static ByteBuffer capacity(ByteBuffer old, int bytes) {
        if (old.capacity() < bytes) old = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        return old.clear();
    }

    private static GpuBuffer grow(GpuBuffer old, long bytes, String name) {
        if (old != null && old.size() >= bytes) return old;
        var next = Iris.getRenderBackend().createStorageBuffer(name, bytes, null);
        if (old != null) Iris.getRenderBackend().retire(old::close);
        return next;
    }

    @Override
    public boolean drawOpaqueSolid(ChunkRenderMatrices matrices, RenderSectionManager manager,
                                   Collection<RenderRegion> selfEnum) {
        return draw(manager, false);
    }

    @Override
    public boolean drawShadowTerrain(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        return draw(manager, true);
    }

    private boolean draw(RenderSectionManager manager, boolean shadow) {
        capture(manager);
        if (!regions.isEmpty()) {
            submit(0, shadow);
            submit(1, shadow);
        }
        return true;
    }

    private void capture(RenderSectionManager manager) {
        GuestTerrainGate.packActive = GuestTerrainGate.enabled() && Iris.isPackInUseQuick();
        captured = true;
        hasTranslucent = false;
        translucentCommandsReady = false;
        regions.clear();
        var lists = manager.getRenderLists().iterator(false);
        List<long[]> nativeMasks = new ArrayList<>();
        int maxIndices = 0;
        while (lists.hasNext()) {
            var list = lists.next();
            var region = list.getRegion();
            var resources = region.getResources();
            if (resources == null || resources.getGeometryBuffer() == null || resources.getGeometryBuffer().isClosed())
                continue;
            if (!registry.isLive(region.getId())) registry.markRegion(region);
            regions.add(region);
            nativeMasks.add(((ChunkRenderListAccessor) list).flywheel$sectionsWithGeometryMap());
            var sections = list.sectionsWithSpritesIterator();
            if (sections != null) while (sections.hasNext()) {
                var sprites = region.getAnimatedSprites(sections.nextByteAsInt());
                if (sprites == null) continue;
                for (var sprite : sprites) SpriteUtil.INSTANCE.markSpriteActive(sprite);
            }
        }
        registry.flushPendingUploads();
        if (regions.isEmpty()) return;
        int count = regions.size();
        regionData = capacity(regionData, count * 16);
        masks = capacity(masks, count * 32);
        clocks = capacity(clocks, count * 4);
        long now = System.currentTimeMillis();
        int run = 0;
        for (int slot = 0; slot < count; slot++) {
            var region = regions.get(slot);
            hasTranslucent |= registry.hasTranslucentPresent(region.getId());
            if (slot > 0 && region.getResources().getGeometryBuffer() != regions.get(slot - 1).getResources()
                                                                                .getGeometryBuffer()) run = slot;
            TerrainRegionInput.write(MemoryUtil.memAddress(regionData) + slot * 16L,
                    region.getChunkX(), region.getChunkY(), region.getChunkZ(), region.getId(), run);
            for (long word : nativeMasks.get(slot)) masks.putLong(word);
            clocks.putInt(Math.toIntExact(now - region.getCreationTime()));
            maxIndices = Math.max(maxIndices, Math.max(registry.translucentMaxIndexCount(region.getId()),
                    Math.max(registry.maxIndexCount(0, region.getId()), registry.maxIndexCount(1, region.getId()))));
        }
        indices.ensureCapacity(maxIndices);
        regionBuffer = grow(regionBuffer, count * 16L, "flywheel guest terrain regions");
        visibility = grow(visibility, count * 32L, "flywheel guest terrain native visibility");
        times = grow(times, count * 4L, "flywheel guest terrain clocks");
        commands = grow(commands, count * COMMAND_BYTES, "flywheel guest terrain commands");
        counts = grow(counts, count * 4L, "flywheel guest terrain draw counts");
        countData = capacity(countData, count * 4);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(regionBuffer.slice(0, count * 16L), regionData.limit(count * 16));
        encoder.writeToBuffer(visibility.slice(0, count * 32L), masks.flip());
        encoder.writeToBuffer(times.slice(0, count * 4L), clocks.flip());
    }

    private void submit(int terrainPass, boolean shadow) {
        var program = ExperimentalPipelines.terrain(terrainPass != 0, terrainPass == 2, shadow);
        prepareCommands(terrainPass, shadow);
        record(program);
    }

    private void prepareCommands(int terrainPass, boolean shadow) {
        int count = regions.size();
        boolean translucent = terrainPass == 2;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        ByteBuffer parameters = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        parameters.putInt(GuestTerrainGate.cameraIntX).putInt(GuestTerrainGate.cameraIntY)
                  .putInt(GuestTerrainGate.cameraIntZ).putInt(shadow ? 1 : translucent ? 2 : 0).flip();
        var params = encoder.transientMemory().uploadGpu(parameters,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        encoder.writeToBuffer(counts.slice(0, count * 4L), countData.position(0).limit(count * 4));
        cullBuffers = Map.of("_FlwTerrainRegions", List.of(regionBuffer.slice()),
                "_FlwTerrainSections",
                List.of(translucent ? registry.translucentSectionDataSlice() : registry.sectionDataSlice(terrainPass)),
                "_FlwTerrainVisibility", List.of(visibility.slice()), "_FlwTerrainCommands", List.of(commands.slice()),
                "_FlwTerrainCounts", List.of(counts.slice()),
                "_FlwTerrainCull", List.of(params));
        Iris.getRenderBackend().commands().dispatch(cull, new Vector3i(count, 1, 1), null);
    }

    private void record(ExperimentalPipelines.Program program) {
        int count = regions.size();
        try (var pass = program.open()) {
            Map<String, List<GpuBufferSlice>> buffers = new HashMap<>();
            buffers.put("u_Globals", List.of(GuestTerrainGate.globals()));
            buffers.put("iris_Globals", List.of(RenderSystem.getGlobalSettingsUniform().slice()));
            buffers.put("_flw_RegionInputBuf", List.of(regionBuffer.slice()));
            buffers.put("_flw_RegionTimeBuf", List.of(times.slice()));
            var texels = Map.of("u_SectionTimeInfo", ((WorldPassCommands) pass).getTexelBufferView(
                    GuestTerrainGate.sectionTimeInfo().slice(), GpuFormat.R32_SINT));
            var texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                                   .getTextureView();
            program.bind(pass, buffers, texels, texture,
                    RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST, true));
            pass.setIndexBuffer(indices.getBufferObject(), IndexType.INT);
            for (int first = 0; first < count; ) {
                GpuBuffer geometry = regions.get(first).getResources().getGeometryBuffer();
                int end = first + 1;
                while (end < count && regions.get(end).getResources().getGeometryBuffer() == geometry) end++;
                pass.setVertexBuffer(0, geometry.slice());
                ((IndirectCommands) pass).flywheel$drawIndexedIndirectCount(
                        commands.slice(first * COMMAND_BYTES, (end - first) * COMMAND_BYTES),
                        counts.slice(first * 4L, 4),
                        (end - first) * COMMANDS_PER_REGION);
                first = end;
            }
        }
    }

    @Override
    public void prepareResidentTranslucent(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        capture(manager);
    }

    @Override
    public void captureTranslucentArena(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        capture(manager);
    }

    @Override
    public SodiumTerrainOitReplay translucentOitReplay() {
        return captured && hasTranslucent && capturesTerrain && BackendConfig.INSTANCE.terrainMode()
                                                                                      .compositesTranslucent()
                ? this : null;
    }

    boolean drawCaptured() {
        if (!captured || !capturesTerrain) return false;
        if (!regions.isEmpty()) {
            if (!translucentCommandsReady) {
                prepareCommands(2, false);
                translucentCommandsReady = true;
            }
            record(ExperimentalPipelines.terrain(true, true, false));
        }
        return true;
    }

    void drawWavelet(OitPass pass) {
        if (regions.isEmpty()) return;
        if (!translucentCommandsReady) {
            prepareCommands(2, false);
            translucentCommandsReady = true;
        }
        record(ExperimentalPipelines.terrain(true, true, false, pass));
    }

    @Override
    public void prepareCull(GpuTextureView depth, int width, int height,
                            boolean insert) {
        if (!regions.isEmpty()) prepareCommands(2, false);
        translucentCommandsReady = true;
    }

    @Override
    public void replay(RenderPass pass,
                       OitMode mode,
                       OitFramebuffer framebuffer,
                       GpuTextureView lightmap,
                       GpuTextureView noise,
                       GpuSampler linear,
                       GpuSampler oit,
                       GpuSampler noiseSampler) {
        throw new UnsupportedOperationException("Experimental Iris terrain requires an Iris-owned producer pass");
    }

    @Override
    public void replayGuest(RenderPipeline producer, int pass,
                            GpuTextureView lightmap,
                            GpuSampler sampler) {
        if (pass == 3) drawCaptured();
        else drawWavelet(OitPass.values()[pass]);
    }

    @Override
    public void publishRegistry() {
        registry.publish();
    }

    @Override
    public void unpublishRegistry() {
        registry.unpublish();
    }

    @Override
    public void endFrame() {
        captured = false;
        translucentCommandsReady = false;
    }

    @Override
    public void delete() {
        GuestTerrainGate.packActive = false;
        registry.delete();
        indices.delete();
        Iris.getRenderBackend().retire(cull::close);
        for (var buffer : new GpuBuffer[]{regionBuffer, visibility, times, commands, counts}) {
            if (buffer != null) Iris.getRenderBackend().retire(buffer::close);
        }
    }
}
