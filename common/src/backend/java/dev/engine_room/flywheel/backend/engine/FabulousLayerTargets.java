package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * The Improved Transparency reroute's item-entity + particles layer targets. Vanilla Fabulous sorts these as
 * separate screen layers; the reroute nulls the post-chain (so vanilla never allocates them) and instead
 * redirects {@code LevelRenderer.itemEntityTarget()/particlesTarget()} here while the level frame's
 * translucent feature window executes. Vanilla's own draws land in these targets -- depth-seeded from the
 * opaque main depth, mirroring vanilla's Fabulous prep (LevelRenderer copies main depth into exactly these
 * targets after the solid features) -- and the OIT chain consumes each as ONE resolved translucent surface
 * per pixel. Render-thread only.
 */
public final class FabulousLayerTargets {
    private static final Vector4fc CLEAR_COLOR = new Vector4f(0.0f);
    // RGBA8 color + D32 depth, matching vanilla's screen-size layer descriptor.
    private static final long BYTES_PER_PIXEL = 8L;

    @Nullable
    private static TextureTarget itemEntity;
    @Nullable
    private static TextureTarget particles;
    // True between the level frame's executeTranslucent HEAD and executeTranslucentAfterTerrain RETURN --
    // the only span where the redirected getters resolve here. Outside it (hand/GUI/PiP feature frames run
    // the same PreparedFrame methods) the getters fall through to vanilla's null-when-chainless behavior.
    private static boolean windowOpen;
    // Raised when a redirected consumer resolved the target this frame (both consumers resolve immediately
    // before drawing), so the chain skips the fullscreen replay for untouched layers.
    private static boolean itemTouched;
    private static boolean particlesTouched;

    private FabulousLayerTargets() {
    }

    /**
     * Size + clear both layers, seed their depth from the opaque main depth, and open the redirect window.
     * MUST run at the level frame's executeTranslucent HEAD: after the solid features (so the seed depth
     * includes them, like vanilla's copy point), before any translucent feature draw.
     */
    public static void prepare() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        itemEntity = prepareTarget(itemEntity, "flywheel:fabulous/item_entity", main);
        particles = prepareTarget(particles, "flywheel:fabulous/particles", main);
        windowOpen = true;
        itemTouched = false;
        particlesTouched = false;
    }

    private static TextureTarget prepareTarget(@Nullable TextureTarget target, String label, RenderTarget main) {
        if (target == null) {
            target = new TextureTarget(label, main.width, main.height, true, GpuFormat.RGBA8_UNORM);
            FlwMemoryTracker._allocGpuMemory((long) main.width * main.height * BYTES_PER_PIXEL);
        } else if (target.width != main.width || target.height != main.height) {
            FlwMemoryTracker._freeGpuMemory((long) target.width * target.height * BYTES_PER_PIXEL);
            target.resize(main.width, main.height);
            FlwMemoryTracker._allocGpuMemory((long) main.width * main.height * BYTES_PER_PIXEL);
        }
        RenderSystem.getDevice()
                    .createCommandEncoder()
                    .clearColorAndDepthTextures(target.getColorTexture(), CLEAR_COLOR, target.getDepthTexture(), 0.0);
        // Plain D32 depth, same as any vanilla TextureTarget. NeoForge's patched RenderTarget can give MAIN
        // a combined depth-stencil format when a mod opts in (a NeoForge-only extension invisible to this
        // common sourceset); copyDepthFrom then mismatches and fails loudly -- acceptable until a real mod
        // interaction demands a loader-split allocation.
        target.copyDepthFrom(main);
        return target;
    }

    /**
     * Close the redirect window; the touched flags stay readable until the next {@link #prepare}.
     */
    public static void closeWindow() {
        windowOpen = false;
    }

    public static boolean windowOpen() {
        return windowOpen;
    }

    /**
     * The redirected {@code itemEntityTarget()}; null outside the window (vanilla resolution applies).
     */
    @Nullable
    public static RenderTarget redirectItemEntity() {
        if (!windowOpen) {
            return null;
        }
        itemTouched = true;
        return itemEntity;
    }

    /**
     * The redirected {@code particlesTarget()}; null outside the window (vanilla resolution applies).
     */
    @Nullable
    public static RenderTarget redirectParticles() {
        if (!windowOpen) {
            return null;
        }
        particlesTouched = true;
        return particles;
    }

    @Nullable
    public static GpuTextureView itemColorView() {
        return itemTouched && itemEntity != null ? itemEntity.getColorTextureView() : null;
    }

    @Nullable
    public static GpuTextureView itemDepthView() {
        return itemTouched && itemEntity != null ? itemEntity.getDepthTextureView() : null;
    }

    @Nullable
    public static GpuTextureView particleColorView() {
        return particlesTouched && particles != null ? particles.getColorTextureView() : null;
    }

    @Nullable
    public static GpuTextureView particleDepthView() {
        return particlesTouched && particles != null ? particles.getDepthTextureView() : null;
    }

    /**
     * Debug: alpha-blit the RAW layer colors straight over the given output, bypassing the OIT replay --
     * shows exactly what vanilla drew into the redirected targets and where alpha is non-zero.
     */
    public static void debugBlit(GpuTextureView outColor, GpuTextureView outDepth) {
        if (itemTouched && itemEntity != null) {
            itemEntity.blitAndBlendToTexture(outColor, outDepth);
        }
        if (particlesTouched && particles != null) {
            particles.blitAndBlendToTexture(outColor, outDepth);
        }
    }

    public static void delete() {
        windowOpen = false;
        itemTouched = false;
        particlesTouched = false;
        if (itemEntity != null) {
            FlwMemoryTracker._freeGpuMemory((long) itemEntity.width * itemEntity.height * BYTES_PER_PIXEL);
            itemEntity.destroyBuffers();
            itemEntity = null;
        }
        if (particles != null) {
            FlwMemoryTracker._freeGpuMemory((long) particles.width * particles.height * BYTES_PER_PIXEL);
            particles.destroyBuffers();
            particles = null;
        }
    }
}
