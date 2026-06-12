package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;

/**
 * Replays Sodium's live TRANSLUCENT terrain geometry into the flywheel OIT producer passes -- the live-arena
 * analogue of {@link ChunkTranslucentReplay}. Rather than a CPU repack of Sodium's translucent geometry to vanilla
 * BLOCK + a synthesized {@code ChunkSectionsToRender}, T4 reads Sodium's live {@code CompactChunkVertex}
 * arena (the same arena the opaque T2 path reads) and decodes it GPU-side, with no repack.
 *
 * <p>OIT is order-independent, so Sodium's per-section translucent sort (an INDEX-only permutation) is bypassed:
 * a sequential quad index over the unsorted vertex range yields identical geometry. The implementation lives in
 * {@code TerrainDrawDispatcher} (which owns the Sodium-arena handles + the per-region/per-section batch captured at
 * the opaque seam); this interface is the thread-down handle the OIT seam invokes once per producer mode.
 */
public interface SodiumTerrainOitReplay {
    /**
     * Build the translucent command streams on the GPU (cull against the current OIT-target depth), ONCE per frame,
     * BEFORE any OIT producer pass opens -- compute cannot run inside a RenderPass. The GPU-driven (INDIRECT) path runs
     * the cull/build compute tail here; the CPU per-section (instancing) path no-ops. {@code depthView} is the
     * OIT target's depth (= the depth the producers test against), with its dimensions.
     */
    void prepareCull(GpuTextureView depthView, int width, int height);

    /**
     * Replay the captured translucent sections into an open OIT producer pass (depthRange / coefficients /
     * accumulate). Invoked once per mode; only the bound pipeline + sampler set differ. Mirrors
     * {@link ChunkTranslucentReplay#replay}'s signature so the seam threads it identically. On the GPU-driven path
     * this issues glMultiDrawElementsIndirectCount over the streams {@link #prepareCull} built.
     */
    void replay(RenderPass pass, OitMode mode, OitFramebuffer framebuffer,
                GpuTextureView lightmapView, GpuTextureView blueNoiseView,
                GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler);

    /**
     * Whether this replay supports the insert (single-geometry-pass) OIT path: true for the GPU-driven translucent
     * batch (the MDI stream and the mesh-shader strategy both have insert twins). A {@code false} keeps the whole
     * frame on the wavelet chain. Default {@code false} (the VK / no-op impls).
     */
    default boolean supportsInsert() {
        return false;
    }

    /**
     * Replay the captured translucent sections into an open INSERT producer pass (one call, no OIT-read samplers):
     * the MDI streams draw with the {@code chunkSodiumMlab} insert pipeline (a registered mesh strategy draws via
     * its {@code drawInsert} instead), writing the per-pixel sample SSBOs the chain raw-bound. Only invoked when
     * {@link #supportsInsert()} is {@code true}.
     */
    default void replayInsert(RenderPass pass, OitInsertMode mode, GpuTextureView lightmapView,
                              GpuSampler clampLinear) {
    }
}
