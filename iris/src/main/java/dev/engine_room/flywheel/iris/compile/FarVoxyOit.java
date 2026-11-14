package dev.engine_room.flywheel.iris.compile;

#if MC >= 26.3

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.impl.compat.NativeWaterShaders;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Exact native Voxy water stages and retained hits replayed through one accepted post-pack forward graph.
 */
public final class FarVoxyOit implements AutoCloseable {
    private final IrisVoxyRenderPipeline pipeline;
    private final @Nullable FarHostOit graph;
    private final boolean unblended;
    private final int textureBase;
    private final AsyncGlProgram[] water;
    private @Nullable NativeWaterShaders source;

    public FarVoxyOit(IrisVoxyRenderPipeline pipeline) {
        this.pipeline = pipeline;
        var iris = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        var patch = iris.voxy$getPatchData();
        var buffers = patch.getTranslucentTargets();
        var resolver = ((IrisRenderingPipelineAccessor) iris).flywheel$resolver();
        var targetSettings = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs().getPackDirectives()
                                                                         .getRenderTargetDirectives()
                                                                         .getRenderTargetSettings();
        var formats = new InternalTextureFormat[buffers.length];
        var blends = new BlendMode[buffers.length];
        // Voxy's blend closure is the authoritative parsed JSON declaration. Capture once per pipeline creation.
        try (var _ = FarGlState.capture()) {
            GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            for (int slot = 0; slot < buffers.length; slot++) GlStateManager._enableBlend(slot);
            if (pipeline._getData().getBlender() != null) pipeline._getData().getBlender().run();
            for (int slot = 0; slot < buffers.length; slot++) {
                formats[slot] = targetSettings.get(buffers[slot]).getInternalFormat();
                if (GL30C.glIsEnabledi(GL11C.GL_BLEND, slot))
                    blends[slot] = new BlendMode(GL30C.glGetIntegeri(GL14C.GL_BLEND_SRC_RGB, slot),
                            GL30C.glGetIntegeri(GL14C.GL_BLEND_DST_RGB, slot),
                            GL30C.glGetIntegeri(GL14C.GL_BLEND_SRC_ALPHA, slot),
                            GL30C.glGetIntegeri(GL14C.GL_BLEND_DST_ALPHA, slot));
            }
        }
        var admitted = GuestProgramResolver.oitProperties(iris, false);
        var profile = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs().flywheel$deferredOit();
        unblended = profile == null && buffers.length > 0 && Arrays.stream(blends).allMatch(Objects::isNull);
        var graph = profile != null ? FarHostOit.deferred(buffers, profile)
                : FarHostOit.create("voxy/translucent", "", pipeline._getData().translucentFragPatch(), buffers,
                formats, blends, admitted == null ? ContractProperties.EMPTY.oit(false) : admitted.oit(false));
        int units = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        textureBase = graph == null || graph.producerTextureCount() <= 3 ? 3 : units - graph.producerTextureCount();
        int packSamplers = pipeline._getData().getImageSet() == null ? 0
                : (int) pipeline._getData().getImageSet().layout().lines().filter(line -> line.contains("uniform"))
                                .count();
        this.graph = graph != null && textureBase >= 6 && textureBase < 6 + packSamplers ? null : graph;
        if (this.graph == null && graph != null) graph.close();
        if (this.graph != null) this.graph.storageOffset(10);
        water = new AsyncGlProgram[this.graph == null ? 0 : this.graph.passCount()];
    }

    public @Nullable FarHostOit graph() {
        return graph;
    }

    /**
     * Whether every Voxy translucent target has blending off (Mellow): each keeps only its nearest surface, so retained
     * records join the native surfaces by depth after Voxy's own draw instead of through a shared graph.
     */
    public boolean unblended() {
        return unblended;
    }

    /**
     * The source owner publishes exact stages after Voxy's own preprocessing, once its native shader links.
     */
    public void source(NativeWaterShaders source) {
        if (this.source == source) return;
        for (var program : water) if (program != null) program.close();
        this.source = source;
        if (graph == null) return;
        for (int pass = 0; pass < water.length; pass++) {
            try {
                var vertex = new Compilation();
                vertex.version(GlslVersion.V460);
                vertex.appendNativeStage(new RawSource("Voxy native water vertex", source.vertexSource()));
                var fragment = new Compilation();
                fragment.version(GlslVersion.V460);
                fragment.appendNativeStage(new RawSource("Voxy shared water fragment",
                        graph.fragment(source.fragmentSource(), pass)));
                water[pass] = AsyncGlProgram.graphics("iris/voxy_water/" + pass, vertex, fragment,
                        source.attributeBindings(), published -> {
                            source.setupBindings(published);
                            graph.registerSamplers(published);
                        });
            } catch (RuntimeException failure) {
                // Voxy disables Iris without reporting exceptions from its section-renderer constructor.
                FlwPrograms.LOGGER.error("Failed to construct shared Iris/Voxy water pass {} for native program {}",
                        pass, source.nativeProgram(), failure);
                throw failure;
            }
        }
    }

    public boolean ready() {
        if (graph == null || !graph.ready() || source == null || source.closed()) return false;
        for (var program : water) if (program == null || program.get() == null) return false;
        return true;
    }

    public void render(Viewport<?> view, Runnable nativeWater, Runnable far) {
        graph.render(pipeline.fbTranslucent.getDepthTex().id, view.width, view.height, view.projection,
                pipeline.properties.isZero2One(), textureBase, pipeline.fbTranslucent.framebuffer.id,
                slot -> pipeline._getData().translucentDrawTargets[slot], nativeWater, far,
                () -> pipeline.setupAndBindTranslucent(view));
    }

    /**
     * Native section rendering binds geometry after its shader; install the replay program at the draw boundary.
     */
    public void bind() {
        if (graph != null && graph.pass() >= 0) {
            GuestSsbos.bindAt((IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable(), 10);
            graph.bindProgram(water[graph.pass()].get());
        }
    }

    @Override
    public void close() {
        for (var program : water) if (program != null) program.close();
        if (graph != null) graph.close();
    }
}

#endif
