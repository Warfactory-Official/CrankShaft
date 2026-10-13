package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.NativeOitScope;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.NativeOitReplay;
import dev.engine_room.flywheel.backend.engine.OitFrame;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.VkFoldedOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.FlwImplXplat;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.event.NativeOitLevelFrame;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Replays the prepared native Improved Transparency producers into the selected engine chain. Native water
 * masks and the full-opacity depth cull retain their positions before cloud depth and the following stages.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererNativeOitMixin implements NativeOitReplay {
    @Shadow @Final private GameRenderer gameRenderer;
    @Shadow @Final private TextureManager textureManager;
    @Shadow @Final private OptionsRenderState optionsRenderState;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private CloudRenderer cloudRenderer;
    @Shadow @Final private WorldBorderRenderer worldBorderRenderer;
    @Shadow @Final private WeatherEffectRenderer weatherEffectRenderer;
    @Shadow @Final private LevelTargetBundle targets;
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow public GpuSampler chunkLayerSampler;

    @Shadow
    protected abstract void executeOitWaterMask(FeatureRenderDispatcher.PreparedFrame frame, RenderTarget target);

    @Shadow
    protected abstract void executeDepthBoundsCull();

    @Unique private @Nullable NativeOitLevelFrame flywheel$level;
    @Unique private Runnable flywheel$drawStage;
    @Unique private Runnable flywheel$drawVulkanCategory;
    @Unique private VkNativeOitPass.BackendFactory flywheel$vulkanBackendFactory;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$initializeNativeOitCallbacks(CallbackInfo ci) {
        // Mixin infers field initializer ranges from line numbers; cache callbacks explicitly after construction.
        flywheel$drawStage = this::flywheel$replayScopedStage;
        flywheel$drawVulkanCategory = this::flywheel$replayVulkanCategory;
        flywheel$vulkanBackendFactory = FlwImplXplat.INSTANCE::createNativeOitVulkanPass;
    }

    // Preserve entry callbacks before taking ownership, while still preceding all native OIT work.
    @Inject(method = "executeOit", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;hasAnyWaterMask()Z",
            ordinal = 0), cancellable = true)
    private void flywheel$ownNativeOit(ChunkSectionsToRender chunks,
                                      FeatureRenderDispatcher.PreparedFrame frame, CallbackInfo ci) {
        LodCompat.captureTerrainDepth(1);
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager == null) {
            return;
        }
        if (flywheel$level != null) {
            throw new IllegalStateException("Native OIT frames cannot nest");
        }
        flywheel$level = new NativeOitLevelFrame(chunks, frame);
        try {
            if (!manager.renderNativeTranslucentOit(this)) {
                throw new IllegalStateException("The active engine could not composite native Improved Transparency");
            }
            LodCompat.captureTerrainDepth(2);
        } finally {
            flywheel$level = null;
        }
        // Compat with Fabric rendering: cancelling executeOit skips its original RETURN callback.
        FlwImplXplat.INSTANCE.dispatchNativeOitComplete((LevelRenderer) (Object) this);
        ci.cancel();
    }

    @Inject(method = "executeOit", at = @At("RETURN"))
    private void flywheel$captureNativeTerrainDepth(CallbackInfo ci) {
        LodCompat.captureTerrainDepth(2);
    }

    @Override
    public void prepareWavelet(OitFrame frame) {
        flywheel$prepareNativeParameters();
        flywheel$prepareTerrainCull(false);
    }

    @Override
    public void prepareInsert(OitFramebuffer framebuffer, OitFrame frame, boolean fused) {
        flywheel$prepareNativeParameters();
        flywheel$prepareTerrainCull(true);
        // 26.3: insert has no bounds stage; native full-opacity culling still precedes terrain/cloud draws.
        if (!fused) flywheel$replayStage(NativeOitLevelFrame.Replay.insertBounds(framebuffer, frame));
    }

    @Override
    public void setTerrain(@Nullable SodiumTerrainOitReplay terrain) {
        flywheel$level().terrain = terrain;
    }

    @Unique
    private NativeOitLevelFrame flywheel$level() {
        return Objects.requireNonNull(flywheel$level, "Native OIT replay outside an engine-owned executeOit");
    }

    @Unique
    private void flywheel$prepareTerrainCull(boolean insert) {
        NativeOitLevelFrame level = flywheel$level();
        if (level.terrain != null) {
            RenderTarget target = gameRenderer.mainRenderTarget();
            level.terrain.prepareCull(
                    ((OitRenderPassProvider.Parameters) level.parameters().terrain()).depthTextureView,
                    target.width, target.height, insert);
        }
    }

    @Unique
    private void flywheel$prepareNativeParameters() {
        NativeOitScope.beginFrame();
        NativeOitLevelFrame level = flywheel$level();
        boolean waterMask = level.prepared.hasAnyWaterMask();
        boolean renderClouds = optionsRenderState.cloudStatus != CloudStatus.OFF
                && ARGB.alpha(levelRenderState.cloudColor) > 0;
        GpuTextureView mainDepth = gameRenderer.mainRenderTarget().getDepthTextureView();
        if (waterMask) {
            executeOitWaterMask(level.prepared, targets.main.get());
        }
        GpuTextureView bounds = targets.depthBounds.get().getColorTextureView();
        GpuTextureView accumulate = targets.accumulate.get().getColorTextureView();
        GpuTextureView[] coefficients = new GpuTextureView[targets.transmittance.size()];
        for (int i = 0; i < coefficients.length; i++) {
            coefficients[i] = targets.transmittance.get(i).get().getColorTextureView();
        }
        var main = new OitRenderPassProvider.Parameters(bounds, coefficients, accumulate, mainDepth);
        var terrain = waterMask ? new OitRenderPassProvider.Parameters(bounds, coefficients, accumulate,
                targets.oitTerrainWithWaterPatchDepth.get().getDepthTextureView()) : main;
        var cloud = renderClouds ? new OitRenderPassProvider.Parameters(
                targets.depthBoundsCulled.get().getColorTextureView(), coefficients, accumulate,
                targets.oitCloudDepth.get().getDepthTextureView()) : null;
        level.parameters(new NativeOitLevelFrame.Parameters(waterMask, renderClouds, mainDepth,
                textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(), main, terrain, cloud));
    }

    @Override
    public void replayWavelet(RenderPassDescriptor descriptor, OitMode mode, boolean emission,
                               OitFramebuffer framebuffer, OitFrame frame) {
        NativeOitScope.runWavelet(descriptor, null, mode, emission, framebuffer, frame, flywheel$drawStage);
    }

    @Override
    public void replayInsert(RenderPassDescriptor descriptor, OitInsertMode mode, @Nullable VkMlabBuffers storage,
                             OitFrame frame) {
        NativeOitScope.runInsert(descriptor, null, mode, storage, frame, flywheel$drawStage);
    }

    @Override
    public boolean hasAdditive() {
        return NativeOitScope.hasAdditive();
    }

    @Override
    public boolean supportsInsert() {
        SodiumTerrainOitReplay terrain = flywheel$level().terrain;
        return terrain == null || (VkContext.isVulkanHost()
                ? terrain instanceof VkFoldedOitReplay : terrain.supportsInsert());
    }

    @Override
    public VkNativeOitPass.Targets vulkanTargets(OitFramebuffer framebuffer, boolean insert) {
        var parameters = flywheel$level().parameters();
        GpuTextureView[] coefficients = new GpuTextureView[insert ? 0 : 4];
        for (int i = 0; i < coefficients.length; i++) coefficients[i] = framebuffer.coefficientsView(i);
        return new VkNativeOitPass.Targets(insert ? targets.depthBounds.get().getColorTextureView()
                : framebuffer.depthBoundsView(),
                targets.depthBoundsCulled.get().getColorTextureView(), coefficients,
                insert ? null : framebuffer.accumulateView(), parameters.mainDepth(),
                parameters.waterMask() ? targets.oitTerrainWithWaterPatchDepth.get().getDepthTextureView() : null,
                parameters.renderClouds() ? targets.oitCloudDepth.get().getDepthTextureView() : null,
                parameters.renderClouds() && !FlwImplXplat.INSTANCE.vanillaOwnsClouds(), framebuffer.emissionView());
    }

    @Override
    public VkNativeOitPass.BackendFactory vulkanBackendFactory() {
        return flywheel$vulkanBackendFactory;
    }

    @Override
    public void replayVulkanCategory(VkNativeOitPass owner, @Nullable OitInsertMode insertMode,
                                     @Nullable VkMlabBuffers storage, OitFramebuffer framebuffer, OitFrame frame) {
        VkNativeOitPass.Phase phase = owner.phase();
        if (phase.insert()) NativeOitScope.runInsert(null, owner, insertMode, storage, frame,
                flywheel$drawVulkanCategory);
        else NativeOitScope.runWavelet(null, owner, phase.mode(), phase.emission(), framebuffer, frame,
                flywheel$drawVulkanCategory);
    }

    @Unique
    private void flywheel$replayVulkanCategory() {
        NativeOitScope scope = Objects.requireNonNull(NativeOitScope.active());
        VkNativeOitPass owner = scope.owner();
        NativeOitLevelFrame level = flywheel$level();
        var parameters = level.parameters();
        OitStage stage = scope.stage();
        try (RenderPass pass = owner.borrow(() -> "Native OIT category")) {
            switch (owner.phase()) {
                case DEPTH_TERRAIN, COEFFICIENT_TERRAIN, EVALUATE_TERRAIN, INSERT_TERRAIN -> {
                    if (level.terrain != null) {
                        VkFoldedOitReplay terrain = (VkFoldedOitReplay) level.terrain;
                        OitFrame frame = scope.frame();
                        if (scope.insertMode() != null) terrain.replayMlab(owner.commandBuffer(), scope.insertMode(),
                                scope.storage(), frame.lightmapView(), frame.loSampler());
                        else terrain.replayFolded(owner.commandBuffer(), scope.mode(), scope.framebuffer(),
                                frame.lightmapView(), frame.blueNoiseView(), frame.loSampler(), frame.oitSampler(),
                                frame.noiseSampler());
                    } else level.chunks.renderOit(chunkLayerSampler, stage, parameters.terrain(),
                            parameters.blockAtlas(), gameRenderer.lightmap());
                }
                case DEPTH_MAIN, COEFFICIENT_MAIN, EVALUATE_MAIN, INSERT_MAIN, EMISSION_MAIN -> {
                    flywheel$replayMainFeatures(stage, pass);
                    if (parameters.renderClouds() && stage != OitStage.DEPTH_BOUNDS) {
                        level.customCloudHandled = FlwImplXplat.INSTANCE.renderCustomCloudsOit(levelRenderState,
                                optionsRenderState.cloudStatus, stage, parameters.mainDepth(), parameters.main());
                    }
                }
                case CULL_BOUNDS -> {
                    GpuTextureView original = owner.input(0);
                    GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                    pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH_BOUNDS));
                    pass.setUniform("InSampler", original, nearest);
                    pass.draw(3, 1, 0, 0);
                    pass.setUniform("DepthBoundsSampler", original, nearest);
                    pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.OIT_DEPTH_BOUNDS_CULL));
                    pass.draw(3, 1, 0, 0);
                    GpuTextureView culled = targets.depthBoundsCulled.get().getColorTextureView();
                    scope.framebuffer().useDepthBounds(culled);
                    parameters.main().setDepthBoundsTargetView(culled);
                    parameters.terrain().setDepthBoundsTargetView(culled);
                    level.customCloudHandled = false;
                }
                case DEPTH_CUSTOM_CLOUD -> level.customCloudHandled =
                        FlwImplXplat.INSTANCE.renderCustomCloudsOit(levelRenderState, optionsRenderState.cloudStatus,
                                stage, parameters.mainDepth(), parameters.main());
                case DEPTH_CLOUD, COEFFICIENT_CLOUD, EVALUATE_CLOUD, INSERT_CLOUD -> {
                    if (!level.customCloudHandled) cloudRenderer.renderOit(optionsRenderState.cloudStatus,
                            stage, parameters.mainDepth(), parameters.cloud());
                }
                case WATER_MASK -> {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH));
                    pass.setUniform("InSampler", parameters.mainDepth(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    pass.draw(3, 1, 0, 0);
                    level.prepared.executeWaterMask(pass);
                }
            }
        }
    }

    @Unique
    private void flywheel$replayMainFeatures(OitStage stage, RenderPass pass) {
        // 26.3: Improved Transparency aliases every translucent feature category to oitTranslucent.
        flywheel$level().prepared.executeOit(stage, pass);
        worldBorderRenderer.renderOit(levelRenderState.worldBorderRenderState, levelRenderState.cameraRenderState.pos,
                optionsRenderState.renderDistance * 16, stage, pass);
        if (!FlwImplXplat.INSTANCE.renderCustomWeatherOit(levelRenderState, stage, pass)) {
            weatherEffectRenderer.renderOit(stage, levelRenderState.weatherRenderState, pass);
        }
        FlwImplXplat.INSTANCE.dispatchNativeOitStage((LevelRenderer) (Object) this, levelRenderState,
                visibleSections, stage, pass);
    }

    @Unique
    private void flywheel$replayScopedStage() {
        flywheel$replayStage(NativeOitLevelFrame.Replay.of(Objects.requireNonNull(NativeOitScope.active())));
    }

    @Unique
    private void flywheel$replayStage(NativeOitLevelFrame.Replay replay) {
        NativeOitLevelFrame level = flywheel$level();
        var parameters = level.parameters();
        OitStage stage = replay.stage();
        // Emission replays main features only: no vanilla terrain or cloud producer is additive.
        if (!replay.emission()) {
            if (level.terrain != null) {
                OitFrame frame = replay.frame();
                try (RenderPass pass = OitRenderPassProvider.createRenderPass(stage,
                        () -> "Engine terrain", parameters.terrain())) {
                    if (replay.insertMode() == null) {
                        level.terrain.replay(pass, replay.mode(), replay.framebuffer(),
                                frame.lightmapView(), frame.blueNoiseView(), frame.loSampler(), frame.oitSampler(),
                                frame.noiseSampler());
                    } else if (level.terrain instanceof VkFoldedOitReplay vulkan) {
                        vulkan.replayMlab(VkContext.renderPass(pass).commandBuffer, replay.insertMode(),
                                replay.storage(), frame.lightmapView(), frame.loSampler());
                    } else {
                        level.terrain.replayInsert(pass, replay.insertMode(), frame.lightmapView(), frame.loSampler());
                    }
                }
            } else {
                // 26.3: native terrain remains active only when the engine does not own its translucent producer.
                level.chunks.renderOit(chunkLayerSampler, stage, parameters.terrain(), parameters.blockAtlas(),
                        gameRenderer.lightmap());
            }
        }
        try (RenderPass pass = OitRenderPassProvider.createRenderPass(stage,
                () -> "Features, World Border, Weather", parameters.main())) {
            flywheel$replayMainFeatures(stage, pass);
        }
        if (stage == OitStage.DEPTH_BOUNDS) {
            if (replay.engineBounds()) {
                flywheel$cullEngineDepthBounds(replay.framebuffer(), parameters.mainDepth());
            } else {
                executeDepthBoundsCull();
            }
            GpuTextureView culled = targets.depthBoundsCulled.get().getColorTextureView();
            parameters.main().setDepthBoundsTargetView(culled);
            parameters.terrain().setDepthBoundsTargetView(culled);
        }
        if (parameters.renderClouds() && !FlwImplXplat.INSTANCE.renderCustomCloudsOit(levelRenderState,
                optionsRenderState.cloudStatus, stage, parameters.mainDepth(), parameters.main())
                && !replay.emission()) {
            cloudRenderer.renderOit(optionsRenderState.cloudStatus, stage, parameters.mainDepth(), parameters.cloud());
        }
        if (stage == OitStage.DEPTH_BOUNDS && parameters.waterMask()) {
            executeOitWaterMask(level.prepared, targets.main.get());
        }
    }

    @Unique
    private void flywheel$cullEngineDepthBounds(OitFramebuffer framebuffer, GpuTextureView mainDepth) {
        GpuTextureView bounds = framebuffer.depthBoundsView();
        GpuTextureView culled = targets.depthBoundsCulled.get().getColorTextureView();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "flywheel:native_oit/cull_bounds")
                .withColorAttachment(culled).withDepthAttachment(mainDepth).build();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH_BOUNDS));
            pass.setUniform("InSampler", bounds, nearest);
            pass.draw(3, 1, 0, 0);
            pass.setUniform("DepthBoundsSampler", bounds, nearest);
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.OIT_DEPTH_BOUNDS_CULL));
            pass.draw(3, 1, 0, 0);
        }
        framebuffer.useDepthBounds(culled);
    }
}
