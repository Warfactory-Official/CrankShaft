package dev.engine_room.flywheel.impl;

#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.lwjgl.vulkan.VkCommandBuffer;
#endif
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.api.internal.DependencyInjection;
import net.minecraft.client.multiplayer.ClientLevel;

#if MC >= 26.3
import java.util.List;
import java.util.function.Supplier;
#endif

public interface FlwImplXplat {
    FlwImplXplat INSTANCE = DependencyInjection.load(FlwImplXplat.class,
            "dev.engine_room.flywheel.impl.FlwImplXplatImpl");

    boolean isModLoaded(String modId);

    void dispatchReloadLevelRendererEvent(ClientLevel level);

    FlwConfig getConfig();

    boolean vanillaOwnsClouds();

    boolean vanillaOwnsWeather();

    #if MC >= 26.3
    boolean renderCustomWeatherOit(LevelRenderState level, OitStage stage, RenderPass pass);

    boolean renderCustomCloudsOit(LevelRenderState level, CloudStatus status, OitStage stage,
                                 GpuTextureView mainDepth, OitRenderPassProvider.Parameters parameters);

    void dispatchNativeOitStage(LevelRenderer renderer, LevelRenderState level,
                               List<SectionRenderDispatcher.RenderSection> visibleSections,
                               OitStage stage, RenderPass pass);

    /** Dispatches the loader's completion callback after an owned native frame, on the render thread. */
    void dispatchNativeOitComplete(LevelRenderer renderer);

    /** Constructs the loader's native backend facade inside an already-owned Vulkan render pass. */
    VulkanRenderPass createNativeOitVulkanPass(
            VkCommandBuffer commandBuffer, RenderPass.RenderArea area,
            int width, int height, boolean stencil, Supplier<String> label);
    #endif
}
