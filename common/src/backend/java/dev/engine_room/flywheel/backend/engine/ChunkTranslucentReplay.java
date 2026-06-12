package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
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
        var drawGroup = sections.drawGroupsPerLayer()
                                .get(ChunkSectionLayer.TRANSLUCENT);
        if (drawGroup == null || drawGroup.isEmpty()) {
            return;
        }

        int maxIndices = sections.maxIndicesRequired();
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = maxIndices == 0 ? null : autoIndices.getBuffer(maxIndices);
        IndexType indexType = maxIndices == 0 ? null : autoIndices.type();

        // Mipmapped clamp-LINEAR == vanilla's chunkLayerSampler. The sharp look comes from flw_chunk_oit.fsh's
        // ported texel-snapping/RGSS, which REQUIRES a LINEAR+mipmap sampler (textureGrad/textureLod). The 1-arg
        // clampLinear the caller passes lacks mipmaps, which broke the RGSS mip taps.
        GpuSampler atlasSampler = RenderSystem.getSamplerCache()
                                              .getClampToEdge(FilterMode.LINEAR, true);

        pass.setPipeline(OitPipelines.chunkProducer(mode));
        pass.bindTexture("Sampler0", sections.textureView(), atlasSampler);
        pass.bindTexture("Sampler2", lightmapView, clampLinear);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);

        GpuBufferSlice[] chunkInfos = sections.chunkSectionInfos();
        for (var draws : drawGroup.values()) {
            if (!draws.isEmpty()) {
                // Vanilla reverses TRANSLUCENT for back-to-front; OIT is order-independent, but mirror it so
                // the per-draw ChunkSection upload + cutout edge cases match vanilla exactly.
                pass.drawMultipleIndexed(draws.reversed(), indexBuffer, indexType, CHUNK_SECTION_UNIFORM, chunkInfos);
            }
        }
    }
}
