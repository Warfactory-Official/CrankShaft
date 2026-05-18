package dev.engine_room.flywheel.impl.visualization.storage;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.api.visual.*;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.ImplDebugFlags;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.queues.SpscUnboundedArrayQueue;
import dev.engine_room.flywheel.lib.task.*;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountedCompleter;

public abstract class Storage<T> {
    // 1.12.2: render-thread writes; worker reads during the snap-rebuild phase. Safe because the
    // frame barrier serializes processQueue against the worker phase — no concurrent writers.
    protected final Map<T, Visual> visuals = new Reference2ObjectOpenHashMap<>();
    protected final PlanMap<DynamicVisual, DynamicVisual.Context> dynamicVisuals = new PlanMap<>();
    protected final PlanMap<TickableVisual, TickableVisual.Context> tickableVisuals = new PlanMap<>();
    protected final List<SimpleDynamicVisual> simpleDynamicVisuals = new ArrayList<>();
    protected final List<SimpleTickableVisual> simpleTickableVisuals = new ArrayList<>();
    protected final LightUpdatedVisualStorage lightUpdatedVisuals = new LightUpdatedVisualStorage();
    protected final ShaderLightVisualStorage shaderLightVisuals = new ShaderLightVisualStorage();

    // 1.12.2: worker-phase accumulator for parallel recreateAll, drained in finalizeRebuild.
    private final ConcurrentHashMap<T, Visual> rebuildBuffer = new ConcurrentHashMap<>();

    public Collection<Visual> getAllVisuals() {
        return visuals.values();
    }

    public Plan<DynamicVisual.Context> framePlan() {
        var update = ConditionalPlan.<DynamicVisual.Context>on(() -> !ImplDebugFlags.PAUSE_UPDATES)
                .then(NestedPlan.of(dynamicVisuals, ForEachPlan.of(() -> simpleDynamicVisuals, SimpleDynamicVisual::beginFrame)));

        // Do light updates regardless.
        return NestedPlan.of(lightUpdatedVisuals.plan(), update);
    }

    public Plan<TickableVisual.Context> tickPlan() {
        return ConditionalPlan.<TickableVisual.Context>on(() -> !ImplDebugFlags.PAUSE_UPDATES)
                .then(NestedPlan.of(tickableVisuals, ForEachPlan.of(() -> simpleTickableVisuals, SimpleTickableVisual::tick)));
    }

    public LightUpdatedVisualStorage lightUpdatedVisuals() {
        return lightUpdatedVisuals;
    }

    public ShaderLightVisualStorage shaderLightVisuals() {
        return shaderLightVisuals;
    }

    /**
     * Default is SPSC (main-thread producer, single worker consumer). Subclasses that introduce
     * a worker-thread producer <b>must</b> override to an MPSC variant — SPSC silently corrupts
     * under concurrent offers.
     */
    public Queue<Transaction<T>> getTransactionQueue() {
        return new SpscUnboundedArrayQueue<>(1024);
    }

    /**
     * Is the given object currently capable of being added?
     *
     * @return true if the object is currently capable of being visualized.
     */
    public abstract boolean willAccept(T obj);

    public void add(VisualizationContext visualizationContext, T obj, float partialTick) {
        Visual visual = visuals.get(obj);

        if (visual == null) {
            visual = createRaw(visualizationContext, obj, partialTick);

            if (visual != null) {
                setup(visual, partialTick);
                visuals.put(obj, visual);
            }
        }
    }

    public void remove(T obj) {
        Visual visual = visuals.remove(obj);

        if (visual == null) {
            return;
        }

        if (visual instanceof DynamicVisual dynamic) {
            if (visual instanceof SimpleDynamicVisual simpleDynamic) {
                simpleDynamicVisuals.remove(simpleDynamic);
            } else {
                dynamicVisuals.remove(dynamic);
            }
        }
        if (visual instanceof TickableVisual tickable) {
            if (visual instanceof SimpleTickableVisual simpleTickable) {
                simpleTickableVisuals.remove(simpleTickable);
            } else {
                tickableVisuals.remove(tickable);
            }
        }
        if (visual instanceof LightUpdatedVisual lightUpdated) {
            lightUpdatedVisuals.remove(lightUpdated);
        }
        if (visual instanceof ShaderLightVisual shaderLight) {
            shaderLightVisuals.remove(shaderLight);
        }

        visual.delete();
    }

    public void update(T obj, float partialTick) {
        Visual visual = visuals.get(obj);

        if (visual == null) {
            return;
        }

        visual.update(partialTick);
    }

    /**
     * Rebuilds every visual against a fresh {@link VisualizationContext} on origin snap. Workers
     * divide-and-conquer the per-visual delete+construct via a key Spliterator; a serial tail
     * swaps the visuals map and re-categorizes into the dynamic/tickable/light-tracked lists.
     */
    public Plan<RenderContext> recreateAllPlan(VisualizationContext visualizationContext) {
        return CountedCompleterPlan.<RenderContext>of((executor, renderCtx, onCompletion) -> {
            clearCategoryLists();
            rebuildBuffer.clear();

            long threshold = Math.max(1L, MoreMath.ceilingDiv(visuals.size(), executor.threadCount() * 4));
            return new RebuildTask<>(null, this, visuals.keySet().spliterator(), threshold,
                    visualizationContext, renderCtx.partialTick(), onCompletion, executor);
        }).then(SimplePlan.of(renderCtx -> finalizeRebuild(renderCtx.partialTick())));
    }

    private void clearCategoryLists() {
        dynamicVisuals.clear();
        tickableVisuals.clear();
        simpleDynamicVisuals.clear();
        simpleTickableVisuals.clear();
        lightUpdatedVisuals.clear();
        shaderLightVisuals.clear();
    }

    // 1.12.2: runs on a single FJP worker (not the render thread). Setup must be serial because
    // the per-category collections are not concurrent.
    private void finalizeRebuild(float partialTick) {
        visuals.clear();
        rebuildBuffer.forEach((key, visual) -> {
            visuals.put(key, visual);
            setup(visual, partialTick);
        });
        rebuildBuffer.clear();
    }

    @Nullable
    protected abstract Visual createRaw(VisualizationContext visualizationContext, T obj, float partialTick);

    private void setup(Visual visual, float partialTick) {
        if (visual instanceof DynamicVisual dynamic) {
            if (visual instanceof SimpleDynamicVisual simpleDynamic) {
                simpleDynamicVisuals.add(simpleDynamic);
            } else {
                dynamicVisuals.add(dynamic, dynamic.planFrame());
            }
        }

        if (visual instanceof TickableVisual tickable) {
            if (visual instanceof SimpleTickableVisual simpleTickable) {
                simpleTickableVisuals.add(simpleTickable);
            } else {
                tickableVisuals.add(tickable, tickable.planTick());
            }
        }

        if (visual instanceof SectionTrackedVisual tracked) {
            SectionTracker tracker = new SectionTracker();

            // Give the visual a chance to invoke the collector.
            tracked.setSectionCollector(tracker);

            if (visual instanceof LightUpdatedVisual lightUpdated) {
                lightUpdatedVisuals.add(lightUpdated, tracker);
                lightUpdated.updateLight(partialTick);
            }

            if (visual instanceof ShaderLightVisual shaderLight) {
                shaderLightVisuals.add(shaderLight, tracker);
            }
        }
    }

    public void invalidate() {
        clearCategoryLists();
        visuals.values().forEach(Visual::delete);
        visuals.clear();
    }

    private static final class RebuildTask<T> extends CountedCompleter<Void> {
        private final Storage<T> storage;
        private final Spliterator<T> spliterator;
        private final long threshold;
        private final VisualizationContext visualizationContext;
        private final float partialTick;
        private final Runnable onCompletion;
        private final TaskExecutor executor;

        RebuildTask(CountedCompleter<?> parent, Storage<T> storage, Spliterator<T> spliterator,
                    long threshold, VisualizationContext visualizationContext, float partialTick,
                    Runnable onCompletion, TaskExecutor executor) {
            super(parent);
            this.storage = storage;
            this.spliterator = spliterator;
            this.threshold = threshold;
            this.visualizationContext = visualizationContext;
            this.partialTick = partialTick;
            this.onCompletion = onCompletion;
            this.executor = executor;
        }

        @Override
        public void compute() {
            try {
                Spliterator<T> sp = spliterator;
                while (sp.estimateSize() > threshold) {
                    Spliterator<T> left = sp.trySplit();
                    if (left == null) {
                        break;
                    }
                    addToPendingCount(1);
                    new RebuildTask<>(this, storage, left, threshold,
                            visualizationContext, partialTick, onCompletion, executor).fork();
                }
                while (sp.tryAdvance(this::processKey)) {
                }
            } catch (Throwable throwable) {
                executor.recordFailure(throwable);
            }
            tryComplete();
        }

        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            if (getCompleter() != null) {
                return;
            }
            onCompletion.run();
        }

        private void processKey(T key) {
            Visual old = storage.visuals.get(key);
            if (old != null) {
                old.delete();
            }
            Visual created = storage.createRaw(visualizationContext, key, partialTick);
            if (created != null) {
                storage.rebuildBuffer.put(key, created);
            }
        }
    }
}
