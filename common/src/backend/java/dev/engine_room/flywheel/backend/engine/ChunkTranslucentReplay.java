package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

import java.util.List;

public final class ChunkTranslucentReplay {
    private static final List<String> CHUNK_SECTION_UNIFORM = List.of("ChunkSection");

    private ChunkTranslucentReplay() {
    }

    public static void replay(RenderPass pass, ChunkSectionsToRender sections, OitMode mode, OitFramebuffer framebuffer,
                              GpuTextureView lightmapView, GpuTextureView blueNoiseView,
                              GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler) {
        #if MC >= 26.3
        var separate = OitPipelines.chunkProducer(mode, false);
        var indirect = OitPipelines.chunkProducer(mode, true);
        pass.setUniform("Sampler0", ChunkReplayData.textureView(), TerrainAtlasFilter.sampler());
        pass.setUniform("Sampler2", lightmapView, clampLinear);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);
        ChunkReplayData.replay(pass, sections, separate, indirect);
        #else
        var drawGroup = sections.drawGroupsPerLayer()
                                .get(ChunkSectionLayer.TRANSLUCENT);
        if (drawGroup == null || drawGroup.isEmpty()) {
            return;
        }

        int maxIndices = sections.maxIndicesRequired();
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = maxIndices == 0 ? null : autoIndices.getBuffer(maxIndices);
        IndexType indexType = maxIndices == 0 ? null : autoIndices.type();

        pass.setRenderPipeline(OitPipelines.chunkProducer(mode));
        pass.setUniform("Sampler0", sections.textureView(), TerrainAtlasFilter.sampler());
        pass.setUniform("Sampler2", lightmapView, clampLinear);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);

        GpuBufferSlice[] chunkInfos = sections.chunkSectionInfos();
        for (var draws : drawGroup.values()) {
            if (!draws.isEmpty()) {
                // Vanilla reverses TRANSLUCENT for back-to-front; OIT is order-independent, but mirror it so
                // the per-draw ChunkSection upload + cutout edge cases match vanilla exactly.
                pass.drawMultipleIndexed(draws.reversed(), indexBuffer, indexType, CHUNK_SECTION_UNIFORM, chunkInfos);
            }
        }
        #endif
    }
}
