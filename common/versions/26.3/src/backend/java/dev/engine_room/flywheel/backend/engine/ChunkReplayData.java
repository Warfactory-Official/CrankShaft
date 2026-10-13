package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureAtlas;

import java.util.List;

/**
 * Vanilla chunk data consumed by the engine's OIT replay. Call on the render thread after frame preparation;
 * all slices and indirect commands remain owned by that frame. Both backends retain vanilla's prepared draw
 * strategy, including indirect commands and the instance-rate section-data stream.
 */
public final class ChunkReplayData {
    private ChunkReplayData() {
    }

    public static GpuTextureView textureView() {
        return Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    }

    public static int maxIndices(ChunkSectionsToRender sections) {
        return sections.maxIndicesRequired;
    }

    public static GpuBufferSlice terrainUniform(ChunkSectionsToRender sections) {
        return sections.terrainTransformUBO;
    }

    public static void replay(RenderPass pass, ChunkSectionsToRender sections, RenderPipeline separate,
                              RenderPipeline indirect) {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = sections.maxIndicesRequired == 0 ? null : indices.getBuffer();
        IndexType indexType = sections.maxIndicesRequired == 0 ? null : indices.type();
        pass.setUniform("TerrainUniform", sections.terrainTransformUBO);
        // Both native strategies preserve their prepared order, per-section data and indirect command offsets.
        sections.render(ChunkSectionLayer.TRANSLUCENT, pass, indexBuffer, indexType, separate, indirect);
    }

    public static List<RenderPass.Draw<GpuBufferSlice[]>> separateDraws(ChunkSectionsToRender sections) {
        return separate(sections).drawsPerLayer.get(ChunkSectionLayer.TRANSLUCENT);
    }

    public static GpuBufferSlice[] separateInfos(ChunkSectionsToRender sections) {
        return separate(sections).chunkSectionInfos;
    }

    private static ChunkSectionsToRender.DrawSeparate separate(ChunkSectionsToRender sections) {
        return (ChunkSectionsToRender.DrawSeparate) sections;
    }
}
