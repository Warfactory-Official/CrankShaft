package dev.engine_room.flywheel.impl.visualization;

import dev.engine_room.flywheel.api.visualization.ConcurrentRenderStateExtraction;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.impl.task.FlwTaskExecutor;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import net.minecraft.client.renderer.blockentity.TrialSpawnerRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.function.IntConsumer;

/**
 * {@link ConcurrentRenderStateExtraction#supports} and the per-frame batch behind it. Within
 * {@code LevelExtractor.extract}'s entity and block entity loops, deferral happens where vanilla calls the dispatcher
 * or renderer, so hooks on the callers ({@code LevelExtractor.extractEntity},
 * {@code BlockEntityRenderDispatcher.tryExtractRenderState}) still run on the render thread. Render thread only.
 */
public final class ConcurrentExtraction {
    // Vanilla extraction touching render-thread-only state: map textures, player skins, Font line splitting, spawners'
    // lazily created display entities.
    private static final Set<Class<?>> VANILLA_EXCLUDED = Set.of(ItemFrameRenderer.class, AvatarRenderer.class,
            DisplayRenderer.TextDisplayRenderer.class, SkullBlockRenderer.class, SpawnerRenderer.class,
            TrialSpawnerRenderer.class);
    // Exact vanilla classes: a mod subclass may override extraction.
    private static final ClassValue<Boolean> SUPPORTED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return ConcurrentRenderStateExtraction.class.isAssignableFrom(type)
                    || type.getName().startsWith("net.minecraft.") && !VANILLA_EXCLUDED.contains(type);
        }
    };
    // Below this, task dispatch costs more than serial extraction.
    private static final int MIN_BATCH = 16;
    private static final int SLICE = 4;

    /**
     * Stands in for a deferred entity state until the batch replaces it.
     */
    public static final EntityRenderState PENDING = new EntityRenderState();

    private static final EntityBatch ENTITIES = new EntityBatch();
    private static final BlockEntityBatch BLOCK_ENTITIES = new BlockEntityBatch();
    private static @Nullable Thread scope;

    private ConcurrentExtraction() {
    }

    public static boolean supports(Object renderer) {
        return FlwConfig.INSTANCE.concurrentExtraction() && SUPPORTED.get(renderer.getClass());
    }

    /**
     * Before {@code LevelExtractor.extract}'s entity loop.
     */
    public static void begin() {
        scope = Thread.currentThread();
    }

    /**
     * After its block entity loop: fills every deferred state.
     */
    public static void end(EntityRenderDispatcher dispatcher) {
        scope = null;
        ENTITIES.run(dispatcher);
        BLOCK_ENTITIES.run();
    }

    /**
     * Inside {@code LevelExtractor.extractEntity}: {@code true} means return {@link #PENDING}, then
     * {@link #slotEntity}.
     */
    public static boolean deferEntity(EntityRenderDispatcher dispatcher, Entity entity, float partialTick) {
        if (scope != Thread.currentThread()) {
            return false;
        }
        EntityRenderer<?, ?> renderer = dispatcher.getRenderer(entity);
        return supports(renderer) && ENTITIES.defer(renderer, entity, partialTick);
    }

    /**
     * {@link #PENDING} is about to be appended to {@code output}.
     */
    public static void slotEntity(List<EntityRenderState> output) {
        ENTITIES.slot(output);
    }

    /**
     * Inside {@code BlockEntityRenderDispatcher.tryExtractRenderState}: {@code true} means the batch extracts into
     * {@code state}.
     */
    public static boolean deferBlockEntity(BlockEntityRenderer<?, ?> renderer, BlockEntity blockEntity,
                                           BlockEntityRenderState state, float partialTick, Vec3 cameraPosition,
                                           ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        if (scope != Thread.currentThread() || !supports(renderer)) {
            return false;
        }
        BLOCK_ENTITIES.defer(renderer, blockEntity, state, partialTick, cameraPosition, breakProgress);
        return true;
    }

    private static void forEach(int count, IntConsumer body) {
        ForkJoinPool pool = FlwTaskExecutor.get()
                                           .forkJoinPool();
        if (count < MIN_BATCH || pool == null) {
            for (int i = 0; i < count; i++) {
                body.accept(i);
            }
        } else {
            pool.invoke(new Slice(body, 0, count));
        }
    }

    private static final class Slice extends RecursiveAction {
        private final IntConsumer body;
        private final int from;
        private final int to;

        private Slice(IntConsumer body, int from, int to) {
            this.body = body;
            this.from = from;
            this.to = to;
        }

        @Override
        protected void compute() {
            if (to - from > SLICE) {
                int mid = (from + to) >>> 1;
                invokeAll(new Slice(body, from, mid), new Slice(body, mid, to));
                return;
            }
            for (int i = from; i < to; i++) {
                body.accept(i);
            }
        }
    }

    private static final class EntityBatch {
        // A renderer class's first extraction stays serial: NeoForge's createRenderState fills an unsynchronized
        // per-class cache (RenderStateExtensions) that later extractions only read.
        private final Set<Class<?>> warmed = new HashSet<>();
        private final List<Entity> entities = new ArrayList<>();
        private final FloatArrayList partialTicks = new FloatArrayList();
        private final IntArrayList slots = new IntArrayList();
        private @Nullable List<EntityRenderState> output;

        private boolean defer(EntityRenderer<?, ?> renderer, Entity entity, float partialTick) {
            if (warmed.add(renderer.getClass())) {
                return false;
            }
            entities.add(entity);
            partialTicks.add(partialTick);
            return true;
        }

        private void slot(List<EntityRenderState> output) {
            this.output = output;
            slots.add(output.size());
        }

        private void run(EntityRenderDispatcher dispatcher) {
            if (slots.size() != entities.size()) {
                throw new IllegalStateException("PENDING returned outside LevelExtractor.extractVisibleEntities");
            }
            List<EntityRenderState> output = this.output;
            if (output != null) {
                // Distinct slots: set never resizes, so concurrent sets on an ArrayList are safe.
                forEach(entities.size(), i -> {
                    Entity entity = entities.get(i);
                    EntityRenderState state = dispatcher.extractEntity(entity, partialTicks.getFloat(i));
                    HeldItemHosts.extract(entity, state);
                    output.set(slots.getInt(i), state);
                });
            }
            entities.clear();
            partialTicks.clear();
            slots.clear();
            this.output = null;
        }
    }

    private static final class BlockEntityBatch {
        private final List<BlockEntityRenderer<?, ?>> renderers = new ArrayList<>();
        private final List<BlockEntity> blockEntities = new ArrayList<>();
        private final List<BlockEntityRenderState> states = new ArrayList<>();
        private final FloatArrayList partialTicks = new FloatArrayList();
        private final List<Vec3> cameraPositions = new ArrayList<>();
        private final List<ModelFeatureRenderer.@Nullable CrumblingOverlay> breakProgress = new ArrayList<>();

        private void defer(BlockEntityRenderer<?, ?> renderer, BlockEntity blockEntity, BlockEntityRenderState state,
                           float partialTick, Vec3 cameraPosition,
                           ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
            renderers.add(renderer);
            blockEntities.add(blockEntity);
            states.add(state);
            partialTicks.add(partialTick);
            cameraPositions.add(cameraPosition);
            this.breakProgress.add(breakProgress);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void run() {
            forEach(renderers.size(), i -> ((BlockEntityRenderer) renderers.get(i)).extractRenderState(
                    blockEntities.get(i), states.get(i), partialTicks.getFloat(i), cameraPositions.get(i),
                    breakProgress.get(i)));
            renderers.clear();
            blockEntities.clear();
            states.clear();
            partialTicks.clear();
            cameraPositions.clear();
            breakProgress.clear();
        }
    }
}
