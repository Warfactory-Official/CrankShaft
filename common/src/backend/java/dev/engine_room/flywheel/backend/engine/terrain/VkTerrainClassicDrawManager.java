package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.VkFoldedOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

final class VkTerrainClassicDrawManager implements TerrainDispatcher, SodiumTerrainOitReplay, VkFoldedOitReplay {
    private final VkPrograms programs;
    private final VkDescriptorWriter writer = new VkDescriptorWriter();
    private final List<SectionDraw> draws = new ArrayList<>();
    private final Map<RenderRegion, FadeTimes> fades = new WeakHashMap<>();
    private final SharedQuadIndexBuffer sharedIndices =
            new SharedQuadIndexBuffer(SharedQuadIndexBuffer.IndexFormat.INTEGER);
    private final DynamicUniformStorage<RegionOrigin> origins;
    private final DynamicUniformStorage<ChunkSection> sections;
    private boolean captured;

    VkTerrainClassicDrawManager(VkPrograms programs) {
        this.programs = programs;
        origins = new DynamicUniformStorage<>("flywheel:vk_classic_origins", 16, 256);
        try {
            sections = new DynamicUniformStorage<>("flywheel:vk_classic_sections", 96, 256);
        } catch (BackendUnavailableException e) {
            origins.close();
            throw e;
        }
        programs.acquire();
    }

    @Override
    public boolean drawOpaqueSolid(ChunkRenderMatrices matrices, RenderSectionManager manager,
                                   @Nullable Collection<RenderRegion> selfEnum) {
        if (BackendConfig.INSTANCE.terrainMode().compositesTranslucent()) {
            captureTranslucentArena(matrices, manager);
        }
        return false;
    }

    @Override
    public void prepareResidentTranslucent(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        captureTranslucentArena(matrices, manager);
    }

    @Override
    public void captureTranslucentArena(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        draws.clear();
        captured = true;
        Matrix4fc modelView = new Matrix4f(matrices.modelView());
        long now = Util.getMillis();
        int maxSharedIndices = 0;
        var regions = manager.getRenderLists().iterator(false);
        while (regions.hasNext()) {
            var renderList = regions.next();
            RenderRegion region = renderList.getRegion();
            var resources = region.getResources();
            if (resources == null) {
                continue;
            }
            GpuBuffer geometry = resources.getGeometryBuffer();
            var storage = region.getStorage(DefaultTerrainRenderPasses.TRANSLUCENT);
            var visible = renderList.sectionsWithGeometryIterator(false);
            if (geometry == null || geometry.isClosed() || storage == null || visible == null) {
                continue;
            }
            GpuBuffer indices = resources.getIndexBuffer();
            if (indices != null && indices.isClosed()) {
                indices = null;
            }
            GpuBufferSlice origin = origins.writeUniform(
                    new RegionOrigin(region.getChunkX(), region.getChunkY(), region.getChunkZ()));
            while (visible.hasNext()) {
                int section = visible.nextByteAsInt();
                long ptr = storage.getDataPointer(section);
                long vertexCount = TerrainSectionMath.sumVertexCount(ptr);
                if (vertexCount == 0) {
                    continue;
                }
                int baseVertex = (int) SectionRenderDataUnsafe.getBaseVertex(ptr);
                int indexCount = Math.toIntExact((vertexCount >> 2) * 6L);
                GpuBufferSlice chunk = sections.writeUniform(
                        new ChunkSection(modelView, visibility(region, section, now)));
                if (indices == null) {
                    maxSharedIndices = Math.max(maxSharedIndices, indexCount);
                    draws.add(new SectionDraw(geometry, null, indexCount, 0, baseVertex, origin, chunk));
                } else if (SectionRenderDataUnsafe.isLocalIndex(ptr)) {
                    int firstIndex = Math.toIntExact(SectionRenderDataUnsafe.getBaseElement(ptr));
                    for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                        int vertices = Math.toIntExact(SectionRenderDataUnsafe.getVertexCount(ptr, facing));
                        int count = (vertices >> 2) * 6;
                        if (count != 0) {
                            draws.add(new SectionDraw(geometry, indices, count, firstIndex, baseVertex, origin, chunk));
                        }
                        firstIndex += count;
                        baseVertex += vertices;
                    }
                } else {
                    draws.add(new SectionDraw(geometry, indices, indexCount,
                            Math.toIntExact(SectionRenderDataUnsafe.getBaseElement(ptr)), baseVertex, origin, chunk));
                }
            }
        }
        if (maxSharedIndices > 0) {
            sharedIndices.ensureCapacity(maxSharedIndices);
        }
    }

    private float visibility(RenderRegion region, int section, long now) {
        FadeTimes times = fades.computeIfAbsent(region, ignored -> new FadeTimes());
        if (times.starts[section] == 0L) {
            times.starts[section] = now;
            times.durations[section] = TerrainSectionMath.computeFadeDuration(
                    (region.getChunkX() + TerrainSectionMath.localSectionX(section)) << 4,
                    (region.getChunkY() + TerrainSectionMath.localSectionY(section)) << 4,
                    (region.getChunkZ() + TerrainSectionMath.localSectionZ(section)) << 4);
        }
        long duration = times.durations[section];
        return duration == 0L ? 1.0F : Mth.clamp((float) (now - times.starts[section]) / duration, 0.0F, 1.0F);
    }

    @Override
    public @Nullable SodiumTerrainOitReplay translucentOitReplay() {
        return captured && !draws.isEmpty() ? this : null;
    }

    @Override
    public void prepareCull(GpuTextureView depthView, int width, int height, boolean insert) {
    }

    @Override
    public void replay(RenderPass pass, OitMode mode, OitFramebuffer framebuffer, GpuTextureView lightmapView,
                       GpuTextureView blueNoiseView, GpuSampler clampLinear, GpuSampler oitSampler,
                       GpuSampler noiseSampler) {
        replayFoldedOrUnfolded(((VulkanRenderPass) pass.backend).commandBuffer, mode, framebuffer, lightmapView,
                blueNoiseView, clampLinear, oitSampler, noiseSampler, false);
    }

    @Override
    public void replayFolded(VkCommandBuffer cmd, OitMode mode, OitFramebuffer framebuffer, GpuTextureView lightmapView,
                             GpuTextureView blueNoiseView, GpuSampler clampLinear, GpuSampler oitSampler,
                             GpuSampler noiseSampler) {
        replayFoldedOrUnfolded(cmd, mode, framebuffer, lightmapView, blueNoiseView, clampLinear,
                oitSampler, noiseSampler, true);
    }

    private void replayFoldedOrUnfolded(VkCommandBuffer cmd, OitMode mode, OitFramebuffer framebuffer,
                                       GpuTextureView lightmapView, GpuTextureView blueNoiseView, GpuSampler clampLinear,
                                       GpuSampler oitSampler, GpuSampler noiseSampler, boolean folded) {
        if (!captured || draws.isEmpty()) {
            return;
        }
        VkGraphicsPipeline pipeline = programs.terrain().classicTranslucentProducerPipeline(mode, folded);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        writeCommon(lightmapView, clampLinear);
        if (mode != OitMode.DEPTH_RANGE) {
            long view = VkContext.imageView(framebuffer.depthBoundsView());
            if (folded) {
                writer.inputAttachment(14, view);
            } else {
                writer.sampler(14, view, VkContext.sampler(oitSampler));
            }
            writer.sampler(15, VkContext.imageView(blueNoiseView), VkContext.sampler(noiseSampler));
        }
        if (mode == OitMode.EVALUATE) {
            for (int c = 0; c < 4; c++) {
                long view = VkContext.imageView(framebuffer.coefficientsView(c));
                if (folded) {
                    writer.inputAttachment(24 + c, view);
                } else {
                    writer.sampler(24 + c, view, VkContext.sampler(oitSampler));
                }
            }
        }
        submit(cmd, pipeline);
    }

    @Override
    public void replayMlab(VkCommandBuffer cmd, OitInsertMode mode, VkMlabBuffers mlab, GpuTextureView lightmapView,
                           GpuSampler clampLinear) {
        if (!captured || draws.isEmpty()) {
            return;
        }
        VkGraphicsPipeline pipeline = programs.terrain().classicTranslucentMlabPipeline(mode);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        writeCommon(lightmapView, clampLinear);
        mlab.bind(writer);
        submit(cmd, pipeline);
    }

    private void writeCommon(GpuTextureView lightmapView, GpuSampler clampLinear) {
        Minecraft mc = Minecraft.getInstance();
        long atlasView = VkContext.imageView(mc.getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView());
        writer.sampler(10, atlasView, VkContext.sampler(TerrainAtlasFilter.sampler()))
                .sampler(12, VkContext.imageView(lightmapView), VkContext.sampler(clampLinear))
                .uniform(16, RenderSystem.getProjectionMatrixBuffer())
                .uniform(18, RenderSystem.getShaderFog())
                .uniform(20, RenderSystem.getGlobalSettingsUniform());
    }

    private void submit(VkCommandBuffer cmd, VkGraphicsPipeline pipeline) {
        GpuBuffer vertexBuffer = null;
        GpuBuffer indexBuffer = null;
        for (SectionDraw draw : draws) {
            GpuBuffer drawIndices = draw.indices() == null
                    ? Objects.requireNonNull(sharedIndices.getBufferObject()) : draw.indices();
            if (draw.geometry().isClosed() || drawIndices.isClosed()) {
                continue;
            }
            if (vertexBuffer != draw.geometry()) {
                VkCmd.bindVertexBuffer(cmd, VkContext.buffer(draw.geometry()));
                vertexBuffer = draw.geometry();
            }
            if (indexBuffer != drawIndices) {
                VK12.vkCmdBindIndexBuffer(cmd, VkContext.buffer(drawIndices), 0L, VK12.VK_INDEX_TYPE_UINT32);
                indexBuffer = drawIndices;
            }
            writer.uniform(21, draw.chunk()).uniform(23, draw.origin());
            writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDrawIndexed(cmd, draw.count(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
    }

    @Override
    public void publishRegistry() {
    }

    @Override
    public void unpublishRegistry() {
    }

    @Override
    public void endFrame() {
        captured = false;
        origins.endFrame();
        sections.endFrame();
    }

    @Override
    public void delete() {
        draws.clear();
        fades.clear();
        sharedIndices.delete();
        origins.close();
        sections.close();
        programs.release();
    }

    private record SectionDraw(GpuBuffer geometry, @Nullable GpuBuffer indices, int count, int firstIndex, int baseVertex,
                               GpuBufferSlice origin, GpuBufferSlice chunk) {
    }

    private record RegionOrigin(int x, int y, int z) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buffer) {
            buffer.putInt(x).putInt(y).putInt(z).putInt(0);
        }
    }

    private record ChunkSection(Matrix4fc modelView, float visibility) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buffer) {
            modelView.get(0, buffer);
            buffer.putFloat(64, visibility);
            for (int offset = 68; offset < 96; offset += Integer.BYTES) {
                buffer.putInt(offset, 0);
            }
        }
    }

    private static final class FadeTimes {
        final long[] starts = new long[RenderRegion.REGION_SIZE];
        final long[] durations = new long[RenderRegion.REGION_SIZE];
    }
}
