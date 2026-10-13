package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.engine.OitFrame;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;

/**
 * The native producer replay in progress: adapts native producer pipeline lookups and redirects native OIT pass
 * creation to the engine's attachments. Render thread only; replays cannot nest. Separate replays carry the engine
 * producer's descriptor; fused Vulkan replays carry the owning pass, whose current subpass fixes compatibility.
 */
public final class NativeOitScope {
    private static @Nullable NativeOitScope active;
    private static boolean additiveSeen;

    final NativeOitProducers.Variant variant;
    final boolean vulkan;
    private final @Nullable RenderPassDescriptor descriptor;
    final @Nullable VkNativeOitPass owner;
    final @Nullable OitFramebuffer framebuffer;
    final @Nullable VkMlabBuffers storage;
    final OitFrame frame;
    // Vulkan: pipelines prepared for the current pass or subpass, and the most recent selection.
    VkNativeOitPipelines.@Nullable PreparedContext context;
    VkNativeFrontendCompiler.@Nullable CompiledPipeline selected;

    private NativeOitScope(NativeOitProducers.Variant variant, @Nullable RenderPassDescriptor descriptor,
                           @Nullable VkNativeOitPass owner, @Nullable OitFramebuffer framebuffer,
                           @Nullable VkMlabBuffers storage, OitFrame frame) {
        if ((descriptor == null) == (owner == null)) {
            throw new IllegalArgumentException("A native producer replay needs exactly one engine pass");
        }
        this.variant = variant;
        this.vulkan = VkContext.isVulkanHost();
        this.descriptor = descriptor;
        this.owner = owner;
        this.framebuffer = framebuffer;
        this.storage = storage;
        this.frame = frame;
    }

    public static void runWavelet(@Nullable RenderPassDescriptor descriptor, @Nullable VkNativeOitPass owner,
                                  OitMode mode, boolean emission, OitFramebuffer framebuffer, OitFrame frame,
                                  Runnable draw) {
        run(new NativeOitScope(NativeOitProducers.Variant.wavelet(mode, emission), descriptor, owner, framebuffer,
                null, frame), draw);
    }

    /** {@code storage}: the Vulkan chain's insert buffers; null on GL, which binds its storage before the call. */
    public static void runInsert(@Nullable RenderPassDescriptor descriptor, @Nullable VkNativeOitPass owner,
                                 OitInsertMode mode, @Nullable VkMlabBuffers storage, OitFrame frame, Runnable draw) {
        run(new NativeOitScope(NativeOitProducers.Variant.insert(mode), descriptor, owner, null, storage, frame),
                draw);
    }

    private static void run(NativeOitScope scope, Runnable draw) {
        RenderSystem.assertOnRenderThread();
        if (active != null) throw new IllegalStateException("Native OIT producer scopes cannot nest");
        if (scope.owner != null) scope.context = VkNativeOitPipelines.subpassContext(scope);
        active = scope;
        try {
            draw.run();
        } finally {
            active = null;
        }
    }

    public static @Nullable NativeOitScope active() {
        return active;
    }

    /** Native pipeline lookup; null keeps the native pipeline. */
    public static @Nullable CompiledRenderPipeline select(RenderPipeline pipeline) {
        NativeOitScope scope = active;
        if (scope == null) return null;
        return scope.vulkan ? VkNativeOitPipelines.select(scope, pipeline) : NativeOitPipelines.select(scope, pipeline);
    }

    public static void beginFrame() {
        additiveSeen = false;
    }

    /** Whether an actually selected native pipeline contributed additive emission this frame. */
    public static boolean hasAdditive() {
        return additiveSeen;
    }

    static void selected(boolean additive) {
        additiveSeen |= additive;
    }

    /** Fused wavelet subpasses read engine targets as input attachments; emission samples them, insert uses storage. */
    boolean localRead() {
        return owner != null && variant.insert == null && variant != NativeOitProducers.Variant.EMISSION;
    }

    public OitStage stage() {
        return switch (variant.nativeStage) {
            case DEPTH_RANGE -> OitStage.DEPTH_BOUNDS;
            case GENERATE_COEFFICIENTS -> OitStage.TRANSMITTANCE;
            case EVALUATE -> OitStage.ACCUMULATE;
            case OFF -> throw new IllegalStateException("Native replay without a producer stage");
        };
    }

    /** Wavelet engine stage; {@code OFF} for insert. */
    public OitMode mode() {
        return variant.mode;
    }

    public boolean emission() {
        return variant == NativeOitProducers.Variant.EMISSION;
    }

    public boolean vulkan() {
        return vulkan;
    }

    public @Nullable OitInsertMode insertMode() {
        return variant.insert;
    }

    /** Separate replay: the engine producer pass whose attachments native passes take. */
    public @Nullable RenderPassDescriptor descriptor() {
        return descriptor;
    }

    /** Fused Vulkan replay: native passes borrow the owner's current subpass. */
    public @Nullable VkNativeOitPass owner() {
        return owner;
    }

    public @Nullable OitFramebuffer framebuffer() {
        return framebuffer;
    }

    public @Nullable VkMlabBuffers storage() {
        return storage;
    }

    public OitFrame frame() {
        return frame;
    }
}
