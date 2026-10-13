package dev.engine_room.flywheel.impl;

#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
import dev.engine_room.flywheel.backend.compile.VkNativeFrontendCompiler;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.mixin.fabric.LevelRendererRenderContextAccessor;
import it.unimi.dsi.fastutil.longs.LongList;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
#endif
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.api.event.ReloadLevelRendererCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;

#if MC >= 26.3
import java.util.List;
import java.util.function.Supplier;
#endif

public class FlwImplXplatImpl implements FlwImplXplat {
    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public void dispatchReloadLevelRendererEvent(ClientLevel level) {
        ReloadLevelRendererCallback.EVENT.invoker().onReloadLevelRenderer(level);
    }

    @Override
    public FlwConfig getConfig() {
        return FabricFlwConfig.INSTANCE;
    }

    @Override
    public boolean vanillaOwnsClouds() {
        return true;
    }

    @Override
    public boolean vanillaOwnsWeather() {
        return true;
    }

    #if MC >= 26.3
    static void registerNativeVulkanFactories() {
        NativeOitWarmup.customCloudsSupported(false);
        VkNativeFrontendCompiler.registerBackendFactory(
                new VkNativeFrontendCompiler.BackendFactory() {
                    @Override
                    public VulkanRenderPipeline create(
                            VulkanDevice device, long stencil, long depth,
                            long withoutDepth, long layout, long descriptorSetLayout,
                            LongList modules,
                            List<BindGroupLayout.UniformDescription> uniforms) {
                        return new VulkanRenderPipeline(device, depth,
                                withoutDepth, layout, descriptorSetLayout, modules, uniforms);
                    }

                    @Override
                    public void configureStencil(DepthStencilState nativeState,
                                                 VkPipelineDepthStencilStateCreateInfo target) {
                    }
                });
    }

    @Override
    public boolean renderCustomWeatherOit(LevelRenderState level, OitStage stage, RenderPass pass) {
        return false;
    }

    @Override
    public boolean renderCustomCloudsOit(LevelRenderState level, CloudStatus status, OitStage stage,
                                        GpuTextureView mainDepth, OitRenderPassProvider.Parameters parameters) {
        return false;
    }

    @Override
    public void dispatchNativeOitStage(LevelRenderer renderer, LevelRenderState level,
                                      List<SectionRenderDispatcher.RenderSection> visibleSections,
                                      OitStage stage, RenderPass pass) {
    }

    @Override
    public void dispatchNativeOitComplete(LevelRenderer renderer) {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.invoker().afterTranslucentFeatures(
                ((LevelRendererRenderContextAccessor) renderer).flywheel$renderContext());
    }

    @Override
    public VulkanRenderPass createNativeOitVulkanPass(
            VkCommandBuffer commandBuffer, RenderPass.RenderArea area,
            int width, int height, boolean stencil, Supplier<String> label) {
        var encoder = VkContext.encoder();
        return new VulkanRenderPass(
                VkContext.device(), encoder, commandBuffer,
                encoder.checkpointStorage, area, width, height, true, label);
    }
    #endif

}
