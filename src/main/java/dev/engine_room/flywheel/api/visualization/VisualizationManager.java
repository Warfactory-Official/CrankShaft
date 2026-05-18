package dev.engine_room.flywheel.api.visualization;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.SortedSet;

@ApiStatus.NonExtendable
public interface VisualizationManager {
    @Nullable
    static VisualizationManager get(@Nullable IBlockAccess level) {
        if (!(level instanceof World world)) {
            return null;
        }
        return VisualizationManagerImpl.get(world);
    }

    static VisualizationManager getOrThrow(@Nullable IBlockAccess level) {
        if (!(level instanceof World world)) {
            throw new IllegalStateException("Cannot retrieve visualization manager for level '" + level + "'!");
        }
        return VisualizationManagerImpl.getOrThrow(world);
    }

    static boolean supportsVisualization(@Nullable IBlockAccess level) {
        return level instanceof World world && VisualizationManagerImpl.supportsVisualization(world);
    }

    Vec3i renderOrigin();

    /**
     * Cross-mod entry point for renderer mods (Sodium-derived) that bypass vanilla
     * {@code RenderGlobal.renderEntities} and so miss flywheel's own {@code afterEntities} hook.
     * Returns {@code null} outside a frame.
     */
    @Nullable
    RenderContext currentFrameContext();

    VisualManager<TileEntity> blockEntities();

    VisualManager<Entity> entities();

    VisualManager<Effect> effects();

    /**
     * Get the render dispatcher, which can be used to invoke rendering.
     * <b>This should only be used by mods which heavily rewrite rendering to restore compatibility with Flywheel
     * without mixins.</b>
     */
    RenderDispatcher renderDispatcher();

    @ApiStatus.NonExtendable
    interface RenderDispatcher {
        /**
         * Prepare visuals for render.
         *
         * <p>Guaranteed to be called before {@link #afterEntities} and {@link #beforeCrumbling}.
         * <br>Guaranteed to be called after the render thread has processed all light updates.
         * <br>The caller is otherwise free to choose an invocation site, but it is recommended to call
         * this as early as possible to give the VisualizationManager time to process things off-thread.
         */
        void onStartLevelRender(RenderContext ctx);

        /**
         * Render instances.
         *
         * <p>Guaranteed to be called after {@link #onStartLevelRender} and before {@link #beforeCrumbling}.
         * <br>The caller is otherwise free to choose an invocation site, but it is recommended to call
         * this between rendering entities and block entities.
         */
        void afterEntities(RenderContext ctx);

        /**
         * Render crumbling block entities.
         *
         * <p>Guaranteed to be called after {@link #onStartLevelRender} and {@link #afterEntities}
         * @param destructionProgress The destruction progress map from {@link net.minecraft.client.renderer.RenderGlobal RenderGlobal}.
         */
        void beforeCrumbling(RenderContext ctx, Long2ObjectMap<SortedSet<DestroyBlockProgress>> destructionProgress);
    }
}
