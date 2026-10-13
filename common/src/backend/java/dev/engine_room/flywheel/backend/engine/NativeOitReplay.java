package dev.engine_room.flywheel.backend.engine;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
#endif
import org.jspecify.annotations.Nullable;

/**
 * Prepared native translucent producers owned by one level frame; render thread. Separate replays open their own
 * native passes while no engine pass is open; fused Vulkan replays draw into the owner's current subpass. Prepared
 * native buffers remain live until every callback and the engine composite finish.
 */
public interface NativeOitReplay {
    void setTerrain(@Nullable SodiumTerrainOitReplay terrain);

    void prepareWavelet(OitFrame frame);

    /** {@code fused}: the Vulkan subpass graph prepares native depth bounds itself. */
    void prepareInsert(OitFramebuffer framebuffer, OitFrame frame, boolean fused);

    void replayWavelet(RenderPassDescriptor descriptor, OitMode mode, boolean emission,
                       OitFramebuffer framebuffer, OitFrame frame);

    /** {@code storage}: the Vulkan chain's insert buffers; null on GL, which binds its storage before the call. */
    void replayInsert(RenderPassDescriptor descriptor, OitInsertMode mode, @Nullable VkMlabBuffers storage,
                      OitFrame frame);

    #if MC >= 26.3
    VkNativeOitPass.Targets vulkanTargets(OitFramebuffer framebuffer, boolean insert);

    VkNativeOitPass.BackendFactory vulkanBackendFactory();

    /** One actual category/subpass; the owner advances only after every borrowed facade has closed. */
    void replayVulkanCategory(VkNativeOitPass owner, @Nullable OitInsertMode insertMode,
                              @Nullable VkMlabBuffers storage, OitFramebuffer framebuffer, OitFrame frame);
    #endif

    /** Whether an actually selected native pipeline contributes additive emission in this frame. */
    boolean hasAdditive();

    boolean supportsInsert();
}
