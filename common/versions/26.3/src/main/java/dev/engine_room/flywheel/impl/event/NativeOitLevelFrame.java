package dev.engine_room.flywheel.impl.event;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.NativeOitScope;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.OitFrame;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.VkMlabBuffers;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** One engine-owned {@code executeOit}, until the engine composite returns. Render thread only. */
public final class NativeOitLevelFrame {
    public final ChunkSectionsToRender chunks;
    public final FeatureRenderDispatcher.PreparedFrame prepared;
    public @Nullable SodiumTerrainOitReplay terrain;
    /** A main-feature subpass drew custom clouds; the following cloud subpass skips vanilla clouds. */
    public boolean customCloudHandled;
    private @Nullable Parameters parameters;

    public NativeOitLevelFrame(ChunkSectionsToRender chunks, FeatureRenderDispatcher.PreparedFrame prepared) {
        this.chunks = chunks;
        this.prepared = prepared;
    }

    public Parameters parameters() {
        return Objects.requireNonNull(parameters, "Native OIT replay before the chain prepared its parameters");
    }

    public void parameters(Parameters parameters) {
        this.parameters = parameters;
    }

    /** Vanilla producer attachments for this frame. */
    public record Parameters(boolean waterMask, boolean renderClouds, GpuTextureView mainDepth,
                             GpuTextureView blockAtlas, OitRenderPassProvider.Parameters main,
                             OitRenderPassProvider.Parameters terrain,
                             OitRenderPassProvider.@Nullable Parameters cloud) {
    }

    /**
     * One native stage replay. {@code engineBounds}: the bounds stage wrote the engine framebuffer; false = insert
     * preparation into vanilla targets, culled by vanilla.
     */
    public record Replay(OitStage stage, OitMode mode, boolean emission, @Nullable OitInsertMode insertMode,
                         @Nullable OitFramebuffer framebuffer, OitFrame frame, @Nullable VkMlabBuffers storage,
                         boolean engineBounds) {
        public static Replay of(NativeOitScope scope) {
            return new Replay(scope.stage(), scope.mode(), scope.emission(), scope.insertMode(), scope.framebuffer(),
                    scope.frame(), scope.storage(), true);
        }

        public static Replay insertBounds(OitFramebuffer framebuffer, OitFrame frame) {
            return new Replay(OitStage.DEPTH_BOUNDS, OitMode.DEPTH_RANGE, false, null, framebuffer, frame, null, false);
        }
    }
}
