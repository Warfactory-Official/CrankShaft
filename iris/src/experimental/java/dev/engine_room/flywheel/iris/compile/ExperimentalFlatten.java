package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.SyncCommand;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.SamplerHeapLayout;
import org.joml.Vector4f;

import java.util.List;

/**
 * {@link GuestFlattenTarget} on Iris's selected backend.
 */
public final class ExperimentalFlatten {
    static final String SAMPLER = "flywheel/flatten/" + GuestParticleFlatten.TARGET;
    static final String IDS_SAMPLER = "flywheel/flatten/" + GuestParticleFlatten.TARGET_IDS;
    private static final GpuFormat FORMAT = GpuFormat.RGBA16_FLOAT;
    private static final GpuFormat IDS = GpuFormat.R32_FLOAT;
    private static IrisRenderingPipeline owner;
    private static GpuTextureView target;
    private static GpuTextureView ids;
    private static GpuTextureView depth;
    private static IrisRenderingPipeline checked;
    private static boolean active;

    private ExperimentalFlatten() {
    }

    /**
     * {@code GuestPipelines.singleRecordParticles} on this backend.
     */
    public static boolean active(IrisRenderingPipeline parent) {
        if (checked != parent) {
            checked = parent;
            active = ExperimentalOit.blendable(FORMAT) && ExperimentalOit.blendable(IDS)
                    && GuestProgramResolver.singleRecordParticles(parent);
        }
        return active;
    }

    /**
     * Before Iris seals its sampler heap.
     */
    public static void registerSamplers(IrisRenderingPipeline parent) {
        if (!active(parent)) return;
        parent.getPackSamplers().register(SamplerHeapLayout.Type.SAMPLER2D, SAMPLER, true);
        parent.getPackSamplers().register(SamplerHeapLayout.Type.SAMPLER2D, IDS_SAMPLER, true);
    }

    /**
     * Resizes to the main depth, copies it and clears the composite and ids.
     */
    public static void prepare(IrisRenderingPipeline parent) {
        var main = mainDepth(parent);
        allocate(parent);
        Iris.getRenderBackend().createSync().read(main, SyncCommand.Access.TRANSFER)
            .write(depth, SyncCommand.Access.TRANSFER).nextIs(SyncCommand.Stage.TRANSFER).run();
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.texture(), depth.texture(), 0, 0, 0,
                0, 0, main.texture().getWidth(0), main.texture().getHeight(0));
        Iris.getRenderBackend().commands().clearColors(List.of(target), new Vector4f(0, 0, 0, 0));
        Iris.getRenderBackend().commands().clearColors(List.of(ids), new Vector4f(-1, 0, 0, 0));
    }

    static List<GpuTextureView> colors(IrisRenderingPipeline parent) {
        allocate(parent);
        return List.of(target, ids);
    }

    static GpuTextureView depth(IrisRenderingPipeline parent) {
        allocate(parent);
        return depth;
    }

    static GpuTextureView target() {
        return target;
    }

    static GpuTextureView ids() {
        return ids;
    }

    private static void allocate(IrisRenderingPipeline parent) {
        var main = mainDepth(parent).texture();
        if (owner == parent && target.texture().getWidth(0) == main.getWidth(0)
                && target.texture().getHeight(0) == main.getHeight(0)
                && depth.texture().getFormat() == main.getFormat()) return;
        release();
        owner = parent;
        target = RenderSystem.getDevice().createTextureView(RenderSystem.getDevice().createTexture(
                "flywheel particle flatten", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_DST, FORMAT, main.getWidth(0), main.getHeight(0), 1, 1));
        ids = RenderSystem.getDevice().createTextureView(RenderSystem.getDevice().createTexture(
                "flywheel particle flatten ids", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_DST, IDS, main.getWidth(0), main.getHeight(0), 1, 1));
        depth = RenderSystem.getDevice().createTextureView(RenderSystem.getDevice().createTexture(
                "flywheel particle flatten depth", GpuTexture.USAGE_RENDER_ATTACHMENT
                        | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, main.getFormat(),
                main.getWidth(0), main.getHeight(0), 1, 1));
    }

    private static GpuTextureView mainDepth(IrisRenderingPipeline parent) {
        return ((IrisRenderingPipelineAccessor) parent).flywheel$renderTargets().getDepthTextureView(0);
    }

    public static void release(IrisRenderingPipeline pipeline) {
        if (checked == pipeline) checked = null;
        if (owner == pipeline) release();
    }

    private static void release() {
        for (var view : new GpuTextureView[]{target, ids, depth}) {
            if (view == null) continue;
            var texture = view.texture();
            Iris.getRenderBackend().retire(() -> {
                view.close();
                texture.close();
            });
        }
        target = ids = depth = null;
        owner = null;
    }
}
