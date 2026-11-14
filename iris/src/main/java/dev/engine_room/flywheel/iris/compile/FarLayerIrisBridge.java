package dev.engine_room.flywheel.iris.compile;

import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.engine.far.FarLayerView;
import dev.engine_room.flywheel.backend.engine.far.FarStore;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
#if MC >= 26.3
import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
#endif
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import dev.engine_room.flywheel.iris.mixin.DHCompatAccessor;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Preflight and frame ownership shared by the mainline DH/Voxy native translucent producers. Render thread.
 */
public final class FarLayerIrisBridge implements FarVisuals.TranslucentBridge {
    private static @Nullable FarLayerIrisBridge active;
    private @Nullable IrisRenderingPipeline pipeline;
    private @Nullable ShaderSources sources;
    private @Nullable FarLayerCapture capture;
    private @Nullable FarLayerMeshCapture meshes;
    private @Nullable FarStore store;
    private @Nullable RenderContext frame;
    private boolean dhDrawn;
    private @Nullable FarDhOit dhOit;
    private @Nullable FarLayerDhProgram dhNativeView;
    private float dhPartialTicks;
    #if MC >= 26.3
    private @Nullable IrisVoxyRenderPipeline mainGuestHost;
    private @Nullable IrisVoxyRenderPipeline disjointHost;
    private boolean disjoint;
    #endif

    @Override
    public boolean ready(FarStore store) {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline current)) return false;
        if (pipeline != current || sources != FlwPrograms.SOURCES) {
            release();
            pipeline = current;
            sources = FlwPrograms.SOURCES;
            capture = new FarLayerCapture();
            meshes = new FarLayerMeshCapture();
        }
        active = this;
        boolean ready = capture.ready();
        ready &= meshes.ready(store);
        boolean host = false;
        if (DistantHorizonsCompat.ACTIVE
                && current.getDHCompat().getInstance() instanceof DHCompatInternal dh && dh.shouldOverride) {
            var graph = dh.getTranslucentShader().flywheel$hostOit();
            host = graph != null;
            ready &= graph != null && graph.ready();
            if (dh.shouldOverrideShadow) {
                FarLayerDhProgram shadow = dh.getShadowShader().flywheel$layeredFar();
                ready &= shadow != null && shadow.ready();
            }
        }
        #if MC >= 26.3
        mainGuestHost = null;
        if (VoxyCompat.drawsInto(current)) {
            var data = current.voxy$getPipelineData();
            if (data != null && data.thePipeline != null) {
                var program = data.thePipeline.flywheel$layeredFar();
                var graph = data.thePipeline.flywheel$hostOit();
                boolean separate = disjoint(current, data.thePipeline);
                // An unblended host's nearest-surface draw writes its own depth, which main's contract pass would
                // have to write into main depth: its inside-radius records stay main content.
                boolean unblended = graph.unblended() && separate;
                host = program != null && (unblended || program.sharedGraphReady());
                ready &= program != null && program.ready() && (unblended || graph.ready());
                if (separate) {
                    mainGuestHost = data.thePipeline;
                    ready &= FarLayerMainGuest.ready(current);
                }
            } else {
                ready = false;
            }
        }
        #endif
        return host && ready;
    }

    #if MC >= 26.3
    /**
     * Whether none of the pack's Voxy translucent targets is one of its main translucent program's targets.
     */
    private boolean disjoint(IrisRenderingPipeline pipeline, IrisVoxyRenderPipeline voxy) {
        if (disjointHost == voxy) return disjoint;
        var targets = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets();
        int[] main = GuestPipelines.farLayerDrawBuffers(pipeline);
        boolean shared = false;
        for (int texture : voxy._getData().translucentDrawTargets) {
            for (int buffer : main) {
                var target = targets.get(buffer);
                shared |= target != null && (target.getMainTexture() == texture || target.getAltTexture() == texture);
            }
        }
        disjointHost = voxy;
        disjoint = !shared;
        return disjoint;
    }
    #endif

    /**
     * This frame's main-view retained layers inside the vanilla radius for the pack's main translucent program
     * ({@link FarLayerMainGuest}), or null when the active host shades them itself.
     */
    static @Nullable MainGuestView mainGuestView() {
        #if MC >= 26.3
        var bridge = active;
        var far = currentFar();
        if (bridge == null || bridge.mainGuestHost == null || far == null || far.layeredStore() != bridge.store
                || bridge.frame == null || bridge.frame != currentFrame()) return null;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        var main = bridge.frame;
        var graph = bridge.mainGuestHost.flywheel$hostOit().graph();
        var raster = graph == null ? new Matrix4f(main.projection()) : graph.rasterProjection(main.projection());
        boolean hardware = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        var layers = far.layeredView(raster, main.modelView(), target.width, target.height, true, true, hardware);
        return new MainGuestView(main, layers, raster, new Matrix4f(raster).invert(),
                new Matrix4f(main.modelView()).invert(), main.modelView(), main.camera().position(), target.width,
                target.height);
        #else
        return null;
        #endif
    }

    /**
     * At the guest capture seam, within the frame {@link #mainGuestView} described.
     */
    static void drawMainGuest(FarLayerView layers, Consumer<FarLayerCapture.Window> shade) {
        active.draw(layers, shade);
    }

    record MainGuestView(RenderContext frame, FarLayerView layers, Matrix4fc projection, Matrix4fc inverseProjection,
                         Matrix4fc viewToWorld, Matrix4fc worldToView, Vec3 camera, int width, int height) {
    }

    @Override
    public void beginFrame(FarStore store, RenderContext frame, Vec3i _) {
        this.store = store;
        this.frame = frame;
        dhDrawn = false;
    }

    /**
     * Native water and retained surfaces share forward producers or deferred capture/front publication.
     */
    public static boolean dhWater(RenderParams params, Runnable nativeWater) {
        var bridge = active;
        var far = currentFar();
        if (bridge == null || far == null || far.layeredStore() != bridge.store || bridge.frame == null
                || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return false;
        if (!(bridge.pipeline.getDHCompat().getInstance() instanceof DHCompatInternal dh) || !dh.shouldOverride)
            return false;
        var nativeWaterProgram = dh.getTranslucentShader();
        var graph = nativeWaterProgram.flywheel$hostOit();
        var program = nativeWaterProgram.flywheel$layeredFar();
        if (graph == null || !graph.ready()) return false;
        // Replay substitutes the shader receiving Iris's later per-pass fill; refresh the original view first.
        program.prepareLod(NativeDepth.active()
                        ? NativeDhProjection.of(params.apiCopy.nearClipPlane, params.apiCopy.farClipPlane)
                        : DHCompatAccessor.flywheel$getProjection(params.apiCopy.nearClipPlane, params.apiCopy.farClipPlane),
                CapturedRenderingState.INSTANCE.getGbufferModelView(), 0, params.apiCopy.partialTicks);
        bridge.dhPartialTicks = params.apiCopy.partialTicks;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        boolean hardware = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        var rawProjection = NativeDepth.active() ? new Matrix4f(program.lodProjection())
                : new Matrix4f().m22(hardware ? -0.5f : -1.0f).m32(hardware ? 0.5f : 0.0f).mul(program.lodProjection());
        graph.graph().deferredView(dh.getDepthTexNoTranslucent(), ((GlTexture) target.getDepthTexture()).glId(),
                NativeDepth.active() ? bridge.frame.projection()
                        : MatrixUtils.undoRevZ(new Matrix4f(bridge.frame.projection())), bridge.frame.modelView(),
                program.lodModelView(), true, graph.graph().packed() ? 0.5f : 1, 1, 1);
        bridge.dhNativeView = program;
        bridge.dhOit = graph;
        try {
            graph.graph().render(dh.getStoredDepthTex(), target.width, target.height, rawProjection, hardware, 10,
                    dh.getTranslucentFB().getId(), dh.getTranslucentFB()::getColorAttachment,
                    () -> graph.replay(() -> {
                        graph.water().bind();
                        graph.water().fillUniformData(program.lodProjection(), program.lodModelView(), -1000,
                                bridge.dhPartialTicks);
                        nativeWater.run();
                    }), () -> {
                        bridge.dhDrawn = false;
                        dhTranslucent();
                    }, () -> dh.getTranslucentFB().bindAsDrawBuffer());
        } finally {
            bridge.dhOit = null;
            bridge.dhNativeView = null;
        }
        return true;
    }

    /**
     * Host opaque depth is complete; called only within the shared native-water replay graph.
     */
    public static void dhTranslucent() {
        var bridge = active;
        var far = currentFar();
        if (bridge == null || far == null || far.layeredStore() != bridge.store || bridge.frame == null) return;
        if (!(bridge.pipeline.getDHCompat().getInstance() instanceof DHCompatInternal dh) || !dh.shouldOverride) return;
        if (bridge.dhOit == null) return;
        var program = bridge.dhOit.retained();
        var nativeView = bridge.dhNativeView;
        if (program == null || !program.ready() || bridge.dhDrawn) return;
        program.prepareLod(nativeView.lodProjection(), nativeView.lodModelView(), 0, bridge.dhPartialTicks);
        bridge.dhDrawn = true;
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        boolean hardware = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        var rawProjection = NativeDepth.active() ? new Matrix4f(program.lodProjection())
                : new Matrix4f().m22(hardware ? -0.5f : -1.0f).m32(hardware ? 0.5f : 0.0f).mul(program.lodProjection());
        program.opaque(dh.getDepthTexNoTranslucent(), ((GlTexture) target.getDepthTexture()).glId(),
                new Matrix4f(rawProjection).mul(program.lodModelView()),
                new Matrix4f(bridge.frame.projection()).mul(bridge.frame.modelView()), hardware);
        var raster = bridge.dhOit.graph().rasterProjection(rawProjection);
        var view = far.layeredView(raster, program.lodModelView(), target.width, target.height,
                hardware, true, hardware);
        var inverseProjection = new Matrix4f(raster).invert();
        var viewToWorld = new Matrix4f(program.lodModelView()).invert();
        bridge.draw(view, window -> program.draw(window.hits(), window.maximumHits(), target.width, target.height,
                inverseProjection, viewToWorld, program.lodProjection(), program.lodModelView(), false));
        var main = bridge.frame;
        var mainRaster = bridge.dhOit.graph().rasterProjection(main.projection());
        var mainView = far.layeredView(mainRaster, main.modelView(), target.width, target.height,
                true, true, hardware);
        var mainInverse = new Matrix4f(mainRaster).invert();
        var mainToWorld = new Matrix4f(main.modelView()).invert();
        var dhMain = NativeDepth.active() ? main.projection() : MatrixUtils.undoRevZ(new Matrix4f(main.projection()));
        bridge.draw(mainView, window -> program.draw(window.hits(), window.maximumHits(), target.width, target.height,
                mainInverse, mainToWorld, dhMain, main.modelView(), true));
    }

    #if MC >= 26.3
    /**
     * Voxy's original section draw is replayed alongside retained surfaces before the shared resolve; an unblended
     * host draws it unchanged and its retained surfaces after it, each keeping the nearest surface by depth.
     */
    public static boolean voxyWater(IrisVoxyRenderPipeline pipeline, Viewport<?> viewport, Runnable nativeWater) {
        var bridge = active;
        var far = currentFar();
        if (bridge == null || far == null || far.layeredStore() != bridge.store || bridge.frame == null) return false;
        var graph = pipeline.flywheel$hostOit();
        var program = pipeline.flywheel$layeredFar();
        if (program == null || !program.ready()) return false;
        if (graph.unblended()) {
            if (bridge.mainGuestHost != pipeline) return false;
            nativeWater.run();
            try (var _ = FarGlState.capture()) {
                voxyTranslucent(pipeline, viewport,
                        ((GlTexture) Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture()).glId());
            }
            return true;
        }
        if (!graph.ready()) return false;
        graph.graph().deferredView(pipeline.fb.getDepthTex().id,
                ((GlTexture) Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture()).glId(),
                NativeDepth.active() ? bridge.frame.projection()
                        : MatrixUtils.undoRevZ(new Matrix4f(bridge.frame.projection())), bridge.frame.modelView(),
                viewport.modelView, pipeline.properties.isReverseZ(), 1,
                (float) viewport.width / Minecraft.getInstance().gameRenderer.mainRenderTarget().width,
                (float) viewport.height / Minecraft.getInstance().gameRenderer.mainRenderTarget().height);
        graph.render(viewport, nativeWater, () -> voxyTranslucent(pipeline, viewport,
                ((GlTexture) Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture()).glId()));
        return true;
    }

    /**
     * Called within each accepted shared producer or after an unblended native draw, once opaque depth is copied.
     */
    public static void voxyTranslucent(IrisVoxyRenderPipeline pipeline, Viewport<?> viewport, int sourceDepth) {
        var bridge = active;
        var far = currentFar();
        if (bridge == null || far == null || far.layeredStore() != bridge.store || bridge.frame == null) return;
        var program = pipeline.flywheel$layeredFar();
        var hostOit = pipeline.flywheel$hostOit();
        if (program == null || !program.ready() || (hostOit.unblended() ? bridge.mainGuestHost != pipeline
                : !program.sharedGraphReady() || hostOit.graph().pass() < 0)) return;
        boolean hardware = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        boolean zeroToOne = pipeline.properties.isZero2One();
        boolean reverseZ = pipeline.properties.isReverseZ();
        var view = far.layeredView(viewport.projection, viewport.modelView,
                viewport.width, viewport.height, zeroToOne, reverseZ, hardware);
        bridge.draw(view, window -> program.draw(pipeline, viewport, window.hits(), window.maximumHits(),
                viewport.width, viewport.height, sourceDepth, viewport.modelView, viewport.projection,
                bridge.frame.projection(), zeroToOne, reverseZ, true, true, false, 0));
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        var main = bridge.frame;
        // Packs handing Voxy output to main by opaque coverage take the radius's records as main content instead.
        if (bridge.mainGuestHost == pipeline) return;
        var mainView = far.layeredView(main.projection(), main.modelView(),
                viewport.width, viewport.height, true, true, hardware);
        bridge.draw(mainView, window -> program.draw(pipeline, viewport, window.hits(), window.maximumHits(),
                viewport.width, viewport.height, ((GlTexture) target.getDepthTexture()).glId(), main.modelView(),
                main.projection(),
                main.projection(), true, true, true, true, true,
                ((GlTexture) target.getDepthTexture()).glId()));
    }
    #endif

    /**
     * Called at Iris's pre-translucent shadow seam, after DH's native opaque shadow pass supplied its light view.
     */
    public static void dhShadow() {
        var bridge = active;
        if (bridge == null || !DistantHorizonsCompat.ACTIVE) return;
        if (!(bridge.pipeline.getDHCompat().getInstance() instanceof DHCompatInternal dh) || !dh.shouldOverrideShadow)
            return;
        var program = dh.getShadowShader().flywheel$layeredFar();
        if (program == null) return;
        boolean hardware = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        int size = ShadowRenderer.RESOLUTION;
        dhShadow(program.lodProjection(), program.lodModelView(), size, size, hardware);
    }

    /**
     * Native DH shadow stages supply their own light view after the pre-translucent shadow depth copy.
     */
    public static void dhShadow(Matrix4fc projection, Matrix4fc modelView, int width, int height,
                                boolean hardwareZeroToOne) {
        var bridge = active;
        var far = currentFar();
        if (bridge == null || far == null || far.layeredStore() != bridge.store) return;
        if (!(bridge.pipeline.getDHCompat().getInstance() instanceof DHCompatInternal dh) || !dh.shouldOverrideShadow)
            return;
        var program = dh.getShadowShader().flywheel$layeredFar();
        if (program == null || !program.ready()) return;
        boolean reversed = NativeDepth.active();
        var view = far.layeredView(projection, modelView, width, height, reversed && hardwareZeroToOne, reversed,
                hardwareZeroToOne);
        var inverse = new Matrix4f(projection).invert();
        var toWorld = new Matrix4f(modelView).invert();
        bridge.draw(view, window -> program.draw(window.hits(), window.maximumHits(), width, height,
                inverse, toWorld, projection, modelView, false));
    }

    private void draw(FarLayerView view, Consumer<FarLayerCapture.Window> shade) {
        if (!capture.ready()) return;
        meshes.draw(store, view, capture, shade);
        capture.draw(store, view, shade);
        GuestSsbos.restore(pipeline);
    }

    private static @Nullable FarVisuals currentFar() {
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        return manager == null ? null : manager.farVisuals();
    }

    private static @Nullable RenderContext currentFrame() {
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        return manager == null ? null : manager.currentFrameContext();
    }

    /**
     * Resource/program teardown invalidates the borrowed current frame as well as the capture allocations.
     */
    public static void reset() {
        if (active != null) {
            active.release();
            active = null;
        }
        FarLayerMainGuest.reset();
    }

    public void close() {
        release();
        if (active == this) active = null;
    }

    private void release() {
        if (capture != null) capture.close();
        if (meshes != null) meshes.close();
        capture = null;
        meshes = null;
        sources = null;
        pipeline = null;
        store = null;
        frame = null;
        #if MC >= 26.3
        mainGuestHost = null;
        disjointHost = null;
        #endif
    }
}
