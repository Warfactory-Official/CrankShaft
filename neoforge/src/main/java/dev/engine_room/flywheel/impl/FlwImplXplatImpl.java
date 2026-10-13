package dev.engine_room.flywheel.impl;

#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
import dev.engine_room.flywheel.backend.compile.VkNativeFrontendCompiler;
import dev.engine_room.flywheel.backend.vk.VkContext;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
#endif
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.api.event.ReloadLevelRendererEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

#if MC >= 26.3
import java.util.List;
import java.util.function.Supplier;
#endif

public class FlwImplXplatImpl implements FlwImplXplat {
    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void dispatchReloadLevelRendererEvent(ClientLevel level) {
        NeoForge.EVENT_BUS.post(new ReloadLevelRendererEvent(level));
    }

    @Override
    public FlwConfig getConfig() {
        return NeoForgeFlwConfig.INSTANCE;
    }

    @Override
    public boolean vanillaOwnsClouds() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.customCloudsRenderer == null;
    }

    @Override
    public boolean vanillaOwnsWeather() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.customWeatherEffectRenderer == null;
    }

    #if MC >= 26.3
    static void registerNativeVulkanFactories() {
        NativeOitWarmup.customCloudsSupported(true);
        VkNativeFrontendCompiler.registerBackendFactory(
                new VkNativeFrontendCompiler.BackendFactory() {
                    @Override
                    public VulkanRenderPipeline create(
                            VulkanDevice device, long stencil, long depth,
                            long withoutDepth, long layout, long descriptorSetLayout,
                            LongList modules,
                            List<BindGroupLayout.UniformDescription> uniforms) {
                        return new VulkanRenderPipeline(device, stencil, depth,
                                withoutDepth, layout, descriptorSetLayout, modules, uniforms);
                    }

                    @Override
                    public void configureStencil(DepthStencilState nativeState,
                                                 VkPipelineDepthStencilStateCreateInfo target) {
                        var stencil = nativeState.stencilTest();
                        if (stencil == null) return;
                        target.stencilTestEnable(true);
                        target.front().failOp(VulkanConst.toVk(stencil.front().fail()))
                                .passOp(VulkanConst.toVk(stencil.front().pass()))
                                .depthFailOp(VulkanConst.toVk(stencil.front().depthFail()))
                                .compareOp(VulkanConst.toVk(stencil.front().compare()))
                                .compareMask(stencil.readMask()).writeMask(stencil.writeMask())
                                .reference(stencil.referenceValue());
                        target.back().failOp(VulkanConst.toVk(stencil.back().fail()))
                                .passOp(VulkanConst.toVk(stencil.back().pass()))
                                .depthFailOp(VulkanConst.toVk(stencil.back().depthFail()))
                                .compareOp(VulkanConst.toVk(stencil.back().compare()))
                                .compareMask(stencil.readMask()).writeMask(stencil.writeMask())
                                .reference(stencil.referenceValue());
                    }
                });
    }

    @Override
    public boolean renderCustomWeatherOit(LevelRenderState level, OitStage stage, RenderPass pass) {
        return level.customWeatherEffectRenderer != null
                && level.customWeatherEffectRenderer.renderSnowAndRainOit(level, level.weatherRenderState,
                        level.cameraRenderState.pos, stage, pass);
    }

    @Override
    public boolean renderCustomCloudsOit(LevelRenderState level, CloudStatus status, OitStage stage,
                                        GpuTextureView mainDepth, OitRenderPassProvider.Parameters parameters) {
        return level.customCloudsRenderer != null
                && level.customCloudsRenderer.renderCloudsOit(level, status,
                        level.cameraRenderState.viewRotationMatrix, stage, mainDepth, parameters);
    }

    @Override
    public void dispatchNativeOitStage(LevelRenderer renderer, LevelRenderState level,
                                      List<SectionRenderDispatcher.RenderSection> visibleSections,
                                      OitStage stage, RenderPass pass) {
        NeoForge.EVENT_BUS.post(new RenderLevelStageEvent.OitTranslucent(renderer, level, stage, null,
                level.cameraRenderState.viewRotationMatrix, visibleSections, pass));
    }

    @Override
    public void dispatchNativeOitComplete(LevelRenderer renderer) {
    }

    @Override
    public VulkanRenderPass createNativeOitVulkanPass(
            VkCommandBuffer commandBuffer, RenderPass.RenderArea area,
            int width, int height, boolean stencil, Supplier<String> label) {
        var encoder = VkContext.encoder();
        return new VulkanRenderPass(
                VkContext.device(), encoder, commandBuffer,
                encoder.checkpointStorage, area, width, height, true, stencil, label);
    }
    #endif

}
