package dev.engine_room.flywheel.lib.visualization;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.CrumblingPosRedirector;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.extension.EntityExtension;
import dev.engine_room.flywheel.impl.extension.TileEntityExtension;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meldexun.renderlib.api.IBoundingBoxCache;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.MinecraftForgeClient;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Convenience wrappers around the per-level {@link VisualizationManager}. All {@code queue*}
 * methods inherit {@link VisualManager}'s threading contract: <b>main thread only</b>.
 */
public final class VisualizationHelper {
    private static final Comparator<DestroyBlockProgress> CRUMBLING_ORDER =
            Comparator.comparingInt(DestroyBlockProgress::getPartialBlockDamage);

    private VisualizationHelper() {
    }

    /**
     * Dispatch the per-frame afterEntities hook for the manager bound to {@code level}. Pass-0
     * gated (vanilla renderEntities runs twice; Celeritas/Neonium replacements share the same
     * pass-0 contract). No-ops if backend off, manager absent, or beginFrame hasn't yet stored
     * the current frame context.
     */
    public static void dispatchAfterEntities(@Nullable World level) {
        if (MinecraftForgeClient.getRenderPass() != 0) return;
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) return;
        RenderContext ctx = manager.currentFrameContext();
        if (ctx == null) return;
        manager.renderDispatcher().afterEntities(ctx);
    }

    /** Dispatched at the TAIL of vanilla {@code renderBlockLayer(TRANSLUCENT, ...)}. */
    public static void dispatchAfterTranslucent(@Nullable World level) {
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) return;
        RenderContext ctx = manager.currentFrameContext();
        if (ctx == null) return;
        manager.renderDispatcher().afterTranslucent(ctx);
    }

    /**
     * Dispatch beforeCrumbling for the manager bound to {@code level}, regrouping 1.12.2's
     * per-breaker damagedBlocks map into upstream's per-pos shape first. No-ops if there are no
     * damaged blocks, the backend is off, the manager is absent, or beginFrame hasn't yet stored
     * the current frame context.
     */
    public static void dispatchBeforeCrumbling(@Nullable World level,
                                               @Nullable Map<Integer, DestroyBlockProgress> damagedBlocks) {
        if (damagedBlocks == null || damagedBlocks.isEmpty()) return;
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) return;
        RenderContext ctx = manager.currentFrameContext();
        if (ctx == null) return;
        manager.renderDispatcher().beforeCrumbling(ctx, regroupCrumblingByPos(level, damagedBlocks));
    }

    /**
     * Regroup 1.12.2's per-breaker {@link DestroyBlockProgress} map into upstream Flywheel's
     * per-pos shape, applying {@link CrumblingPosRedirector} for multi-block TEs whose visual is
     * owned by a different core position.
     */
    public static Long2ObjectMap<SortedSet<DestroyBlockProgress>> regroupCrumblingByPos(
            World world, Map<Integer, DestroyBlockProgress> damagedBlocks) {
        Long2ObjectOpenHashMap<SortedSet<DestroyBlockProgress>> regrouped = new Long2ObjectOpenHashMap<>();
        for (DestroyBlockProgress progress : damagedBlocks.values()) {
            BlockPos pos = progress.getPosition();
            BlockPos redirected = CrumblingPosRedirector.resolve(world, pos);
            if (redirected != null) pos = redirected;
            long key = pos.toLong();
            SortedSet<DestroyBlockProgress> bucket = regrouped.get(key);
            if (bucket == null) {
                bucket = new TreeSet<>(CRUMBLING_ORDER);
                regrouped.put(key, bucket);
            }
            bucket.add(progress);
        }
        return regrouped;
    }

    public static void queueAdd(Effect effect) {
        VisualizationManager manager = VisualizationManager.get(effect.level());
        if (manager == null) {
            return;
        }

        manager.effects().queueAdd(effect);
    }

    public static void queueRemove(Effect effect) {
        VisualizationManager manager = VisualizationManager.get(effect.level());
        if (manager == null) {
            return;
        }

        manager.effects().queueRemove(effect);
    }

    public static void queueUpdate(TileEntity blockEntity) {
        World level = blockEntity.getWorld();
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) {
            return;
        }

        manager.blockEntities().queueUpdate(blockEntity);
    }

    public static void queueUpdate(Entity entity) {
        World level = entity.world;
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) {
            return;
        }

        manager.entities().queueUpdate(entity);
    }

    public static void queueUpdate(Effect effect) {
        VisualizationManager manager = VisualizationManager.get(effect.level());
        if (manager == null) {
            return;
        }

        manager.effects().queueUpdate(effect);
    }

    // 1.12.2: lookup goes through the per-class flw$visualizer() override injected by
    // VisualizerTransformer; VisualizerRegistry is consulted only as the source of truth for
    // the MutableCallSite target. Reading off the instance lets HotSpot inline the constant load.
    /** @deprecated pulling the visualizer out re-dispatches megamorphically through its interface;
     *  use the injected per-class accessors instead ({@code flw$canVisualize},
     *  {@code flw$skipVanillaRender}, {@code flw$createVisual}). */
    @Deprecated
    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends TileEntity> BlockEntityVisualizer<? super T> getVisualizer(T blockEntity) {
        return (BlockEntityVisualizer<? super T>) ((TileEntityExtension) blockEntity).flw$visualizer();
    }

    /** @deprecated see {@link #getVisualizer(TileEntity)}. */
    @Deprecated
    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends Entity> EntityVisualizer<? super T> getVisualizer(T entity) {
        return (EntityVisualizer<? super T>) ((EntityExtension) entity).flw$visualizer();
    }

    // Frame-invariant cache. Primed at HEAD of each per-frame BE/entity render entry point —
    // vanilla RenderGlobal.renderEntities, RenderLib's TileEntityRenderer/EntityRenderer setup,
    // Sodium-fork SodiumWorldRenderer.renderTileEntities, Celeritas's renderBlockEntities. Per-BE
    // helpers (shouldSkip*, shouldRender, shouldRenderInPass, updateCachedBoundingBox*) read this
    // to avoid ~33k+ per-frame static calls into supportsVisualization. Render-thread only.
    // Defaults to false; calls before any primer has fired simply won't skip, which is safe.
    private static boolean cachedSupportsVisualization;

    public static void cacheSupportsVisualization(@Nullable World level) {
        cachedSupportsVisualization = VisualizationManager.supportsVisualization(level);
    }

    // Installed via RenderGlobalLoopGuardTransformer. The skip checks here and below go through the
    // per-class flw$skipVanillaRender override injected by VisualizerTransformer: the registered
    // visualizer is bound behind a per-class indy constant so its predicate inlines — one virtual call
    // instead of two megamorphic itable hops (flw$visualizer lookup + predicate.test), which dominate
    // these guards at high entity counts. Name labels and leash ropes render as instances
    // (NameTagComponent / LeashComponent), so skipped entities owe vanilla nothing.
    public static boolean shouldSkipEntity(Entity entity) {
        return cachedSupportsVisualization && ((EntityExtension) entity).flw$skipVanillaRender();
    }

    public static boolean shouldSkipTileEntity(TileEntity te) {
        return cachedSupportsVisualization && ((TileEntityExtension) te).flw$skipVanillaRender();
    }

    // Installed via CeleritasRenderGlobalTransformer.
    public static boolean shouldRender(RenderManager renderManager, Entity entity, ICamera camera,
                                       double camX, double camY, double camZ) {
        if (cachedSupportsVisualization && ((EntityExtension) entity).flw$skipVanillaRender()) {
            return false;
        }
        return renderManager.shouldRender(entity, camera, camX, camY, camZ);
    }

    // Installed via SodiumRenderGlobalTransformer.
    public static boolean shouldRenderInPass(Entity entity, int pass) {
        if (cachedSupportsVisualization && ((EntityExtension) entity).flw$skipVanillaRender()) {
            return false;
        }
        return entity.shouldRenderInPass(pass);
    }

    // Installed via RenderLibBoundingBoxCacheTransformer. Unconditional cast: the dispatched
    // call site is statically known to take an Entity.
    public static void updateCachedBoundingBoxEntity(IBoundingBoxCache cache, double partialTicks) {
        if (cachedSupportsVisualization && ((EntityExtension) cache).flw$skipVanillaRender()) {
            return;
        }
        cache.updateCachedBoundingBox(partialTicks);
    }

    // See updateCachedBoundingBoxEntity.
    public static void updateCachedBoundingBoxTile(IBoundingBoxCache cache, double partialTicks) {
        if (cachedSupportsVisualization && ((TileEntityExtension) cache).flw$skipVanillaRender()) {
            return;
        }
        cache.updateCachedBoundingBox(partialTicks);
    }

    public static <T extends TileEntity> boolean tryAddBlockEntity(T blockEntity) {
        World level = blockEntity.getWorld();
        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) {
            return false;
        }

        TileEntityExtension ext = (TileEntityExtension) blockEntity;
        if (!ext.flw$canVisualize()) {
            return false;
        }

        manager.blockEntities().queueAdd(blockEntity);
        return ext.flw$skipVanillaRender();
    }
}
