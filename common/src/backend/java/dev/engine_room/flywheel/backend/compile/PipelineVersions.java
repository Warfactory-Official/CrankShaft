package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link ProgramVersions} of {@link RenderPipelineCompiler} pipelines, with the {@link ProgramGeneration} each
 * covers; render thread.
 */
public final class PipelineVersions {
    private static final Map<RenderPipeline, ProgramGeneration> GENERATIONS = new IdentityHashMap<>();

    public static final ProgramVersions.Tracker<RenderPipeline> PIPELINE = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(RenderPipeline pipeline) {
            return RenderPipelineCompiler.ready(pipeline) ? ProgramVersions.State.PUBLISHED
                    : RenderPipelineCompiler.pending(pipeline) ? ProgramVersions.State.PENDING
                    : ProgramVersions.State.ABSENT;
        }

        @Override
        public void release(RenderPipeline pipeline) {
            GENERATIONS.remove(pipeline);
            RenderPipelineCompiler.release(pipeline);
        }
    };

    /**
     * Pipelines drawn together, published as a unit; null slots are not members.
     */
    public static final ProgramVersions.Tracker<@Nullable RenderPipeline[]> GROUP = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(@Nullable RenderPipeline[] group) {
            ProgramVersions.State state = ProgramVersions.State.PUBLISHED;
            for (RenderPipeline pipeline : group) {
                if (pipeline == null) continue;
                ProgramVersions.State member = PIPELINE.state(pipeline);
                if (member == ProgramVersions.State.ABSENT) return member;
                if (member == ProgramVersions.State.PENDING) state = member;
            }
            return state;
        }

        @Override
        public void release(@Nullable RenderPipeline[] group) {
            for (RenderPipeline pipeline : group) if (pipeline != null) PIPELINE.release(pipeline);
        }
    };

    private PipelineVersions() {
    }

    /**
     * {@link ProgramVersions#select} of {@code key}'s versions; {@code build} requests a pipeline of
     * {@code current}.
     */
    public static <K> @Nullable RenderPipeline select(Map<K, ProgramVersions<RenderPipeline>> versions, K key,
                                                      ProgramGeneration current,
                                                      Function<? super K, RenderPipeline> build) {
        return versions.computeIfAbsent(key, _ -> new ProgramVersions<>(PIPELINE)).select(current, key, k -> {
            RenderPipeline pipeline = build.apply(k);
            GENERATIONS.put(pipeline, current);
            return pipeline;
        });
    }

    /**
     * {@link ProgramVersions#select} of {@code key}'s group versions; {@code build} requests pipelines of
     * {@code current}.
     */
    public static <K> @Nullable RenderPipeline @Nullable [] selectGroup(
            Map<K, ProgramVersions<@Nullable RenderPipeline[]>> versions, K key, ProgramGeneration current,
            Function<? super K, @Nullable RenderPipeline[]> build) {
        return versions.computeIfAbsent(key, _ -> new ProgramVersions<>(GROUP)).select(current, key, k -> {
            RenderPipeline[] group = build.apply(k);
            for (RenderPipeline pipeline : group) if (pipeline != null) GENERATIONS.put(pipeline, current);
            return group;
        });
    }

    /**
     * Records the generation of a pipeline built outside {@link #select}.
     */
    public static void track(RenderPipeline pipeline, ProgramGeneration generation) {
        GENERATIONS.put(pipeline, generation);
    }

    /**
     * Removes a retired pipeline's generation at its owning release boundary. Render thread only.
     */
    public static void untrack(RenderPipeline pipeline) {
        GENERATIONS.remove(pipeline);
    }

    /**
     * The generation a selected pipeline covers; {@link ProgramGeneration#UNBOUNDED} for pipelines keyed by neither
     * registry.
     */
    public static ProgramGeneration generation(RenderPipeline pipeline) {
        return GENERATIONS.getOrDefault(pipeline, ProgramGeneration.UNBOUNDED);
    }
}
