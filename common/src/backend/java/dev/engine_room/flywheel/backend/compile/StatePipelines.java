package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Pipeline groups that differ only in fixed-function state {@code S}, versioned per program key {@code K} across
 * {@link ProgramGeneration}s. A version links its programs for the state that requested it; every other state of the
 * key draws the same programs through wrappers ({@link RenderPipelineCompiler#derive}), so a new state of a built key
 * never waits for a link, and a version keeps serving new states while a newer generation builds. Render thread.
 */
public final class StatePipelines<K, S> {
    private final Builder<K, S> build;
    private final BiConsumer<? super K, RenderPipeline> request;
    private final Map<K, Program> programs = new HashMap<>();

    /**
     * {@code request} compiles a member of the group a version links.
     */
    public StatePipelines(Builder<K, S> build, BiConsumer<? super K, RenderPipeline> request) {
        this.build = build;
        this.request = request;
    }

    @FunctionalInterface
    public interface Builder<K, S> {
        /**
         * The unrequested pipelines of {@code key}'s group in {@code state}, null slots not members, with the shader
         * identifiers of {@code generation}.
         */
        @Nullable RenderPipeline[] build(K key, S state, ProgramGeneration generation);
    }

    /**
     * {@code state}'s group of {@code key}'s newest published version, null before the first or while its wrappers
     * await publication; requests {@code current}'s version. {@code prepare}: called outside any render pass, so the
     * wrappers of a state new to a published version publish at once; otherwise at the next
     * {@link RenderPipelineCompiler#poll}.
     */
    public @Nullable RenderPipeline @Nullable [] select(K key, S state, ProgramGeneration current, boolean prepare) {
        Program program = programs.computeIfAbsent(key, _ -> new Program());
        program.live.removeIf(Version::absent);
        if (program.states.add(state)) {
            for (Version version : program.live) version.wrap(state, prepare);
        }
        Version version = program.versions.select(current, key, k -> program.build(k, state, current, prepare));
        return version == null ? null : version.group(state);
    }

    // Registry-free keys keep PipelineVersions' UNBOUNDED default.
    private static void track(RenderPipeline pipeline, ProgramGeneration generation) {
        if (!generation.equals(ProgramGeneration.NONE)) PipelineVersions.track(pipeline, generation);
    }

    private final class Program {
        private final Set<S> states = new LinkedHashSet<>();
        private final List<Version> live = new ArrayList<>(1);
        private final ProgramVersions<Version> versions = new ProgramVersions<>(new ProgramVersions.Tracker<>() {
            @Override
            public ProgramVersions.State state(Version version) {
                return PipelineVersions.GROUP.state(version.base);
            }

            @Override
            public void release(Version version) {
                live.remove(version);
                version.release();
            }
        });

        private Version build(K key, S state, ProgramGeneration generation, boolean prepare) {
            var base = StatePipelines.this.build.build(key, state, generation);
            for (RenderPipeline pipeline : base) {
                if (pipeline == null) continue;
                track(pipeline, generation);
                request.accept(key, pipeline);
            }
            var version = new Version(key, state, base, generation);
            for (S other : states) if (!other.equals(state)) version.wrap(other, prepare);
            live.add(version);
            return version;
        }
    }

    private final class Version {
        private final K key;
        private final S state;
        private final @Nullable RenderPipeline[] base;
        private final ProgramGeneration generation;
        private final Map<S, @Nullable RenderPipeline[]> wrappers = new HashMap<>();

        private Version(K key, S state, @Nullable RenderPipeline[] base, ProgramGeneration generation) {
            this.key = key;
            this.state = state;
            this.base = base;
            this.generation = generation;
        }

        private void wrap(S other, boolean now) {
            var group = build.build(key, other, generation);
            for (int i = 0; i < group.length; i++) {
                if (group[i] == null) continue;
                track(group[i], generation);
                RenderPipelineCompiler.derive(base[i], group[i], now);
            }
            wrappers.put(other, group);
        }

        private @Nullable RenderPipeline @Nullable [] group(S selected) {
            var group = selected.equals(state) ? base : wrappers.get(selected);
            return PipelineVersions.GROUP.state(group) == ProgramVersions.State.PUBLISHED ? group : null;
        }

        // A pipeline-cache reset left the version's programs; its versions drop it without a release.
        private boolean absent() {
            if (PipelineVersions.GROUP.state(base) != ProgramVersions.State.ABSENT) return false;
            release();
            return true;
        }

        private void release() {
            PipelineVersions.GROUP.release(base);
            wrappers.values().forEach(PipelineVersions.GROUP::release);
        }
    }
}
