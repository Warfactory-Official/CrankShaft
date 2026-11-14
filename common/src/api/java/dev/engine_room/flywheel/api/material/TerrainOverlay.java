package dev.engine_room.flywheel.api.material;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.resources.Identifier;

/**
 * Shading fused into the terrain draw. The source declares {@code vec4 flw_terrainOverlay(vec3 position, vec3 normal, uint receiver)}
 * and returns premultiplied color without discarding. Position is camera-relative, and normal is the receiving
 * triangle's geometric normal; receiver is a captured face ID, or zero. The source reads {@code TerrainOverlayTexture}, {@code TerrainOverlayMetadata},
 * {@code TerrainOverlayLightmap} and its std140 {@code TerrainOverlayData} block. Registration and resource
 * ownership belong to the caller; each source aggregates its active surfaces into one registration per level.
 * <p>
 * Under an Iris shaderpack, only terrain the engine draws (terrain mode other than {@code OFF}) receives overlays.
 * There the result composites onto the pack's albedo samples, so the pack lights it; shadow passes receive none.
 */
public interface TerrainOverlay {
    Identifier fragmentSource();

    /**
     * Render thread, before terrain passes open; {@code frame} changes once per main level render.
     * Removing a registration here excludes it from the frame. Registrations added during a frame are prepared next frame.
     */
    void prepare(RenderContext context, long frame, int width, int height);

    GpuBufferSlice uniforms();

    GpuTextureView texture();

    GpuTextureView metadata();

    GpuSampler sampler();
}
