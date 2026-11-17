package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

import java.util.function.IntConsumer;

/**
 * A raw GL program requested at draw time: linked in the background through {@link GlAsyncCompiler}, or now without
 * parallel compilation; {@code setup} (block, sampler and uniform bindings) runs at publication. Render thread.
 */
public final class GlProgramBuild {
    public static final ProgramVersions.Tracker<GlProgramBuild> TRACKER = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(GlProgramBuild build) {
            return build.handle != 0 ? ProgramVersions.State.PUBLISHED
                    : build.pending != null ? ProgramVersions.State.PENDING : ProgramVersions.State.ABSENT;
        }

        @Override
        public void release(GlProgramBuild build) {
            build.release();
        }
    };

    /**
     * Builds published as a unit; null slots are not members.
     */
    public static final ProgramVersions.Tracker<@Nullable GlProgramBuild[]> GROUP = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(@Nullable GlProgramBuild[] group) {
            ProgramVersions.State state = ProgramVersions.State.PUBLISHED;
            for (GlProgramBuild build : group) {
                if (build == null) continue;
                ProgramVersions.State member = TRACKER.state(build);
                if (member == ProgramVersions.State.ABSENT) return member;
                if (member == ProgramVersions.State.PENDING) state = member;
            }
            return state;
        }

        @Override
        public void release(@Nullable GlProgramBuild[] group) {
            for (GlProgramBuild build : group) if (build != null) build.release();
        }
    };
    private static final String[] NO_ATTRIBUTES = new String[0];

    private int handle;
    private GlAsyncCompiler.@Nullable Pending pending;

    private GlProgramBuild() {
    }

    /**
     * {@code sources}: driver text per stage; a failure throws from the compiling call or the publishing poll,
     * attributed to {@code owner} when non-null.
     */
    public static GlProgramBuild request(String label, int[] types, String[] sources, String[] stageLabels,
                                         ProgramAvailability.@Nullable Feature owner, IntConsumer setup) {
        return request(label, types, sources, stageLabels, NO_ATTRIBUTES, owner, setup);
    }

    /**
     * Native stage specializations retain the original linked vertex attribute locations.
     */
    public static GlProgramBuild request(String label, int[] types, String[] sources, String[] stageLabels,
                                         String[] attributes, ProgramAvailability.@Nullable Feature owner,
                                         IntConsumer setup) {
        RenderSystem.assertOnRenderThread();
        GlProgramBuild build = new GlProgramBuild();
        IntConsumer publish = program -> {
            build.pending = null;
            setup.accept(program);
            build.handle = program;
        };
        if (GlAsyncCompiler.available()) {
            build.pending = GlAsyncCompiler.submit(label, types, sources, stageLabels, attributes, owner, null,
                    publish, () -> build.pending = null);
            return build;
        }
        try (GlCompilationBatch batch = new GlCompilationBatch()) {
            link(batch, label, types, sources, stageLabels, attributes, publish);
            batch.finish(() -> {
            });
        } catch (GlCompilationBatch.Failure failure) {
            if (owner == null) throw failure;
            throw new ProgramAvailability.Failure(owner, failure);
        }
        return build;
    }

    /**
     * Links in {@code batch}, which publishes the build, {@code setup} first, when it finishes.
     */
    public static GlProgramBuild link(GlCompilationBatch batch, String label, int[] types, String[] sources,
                                      String[] stageLabels, IntConsumer setup) {
        GlProgramBuild build = new GlProgramBuild();
        link(batch, label, types, sources, stageLabels, NO_ATTRIBUTES, program -> {
            setup.accept(program);
            build.handle = program;
        });
        return build;
    }

    private static void link(GlCompilationBatch batch, String label, int[] types, String[] sources,
                             String[] stageLabels, String[] attributes, IntConsumer publish) {
        int[] stages = new int[types.length];
        for (int i = 0; i < types.length; i++) stages[i] = batch.shader(types[i], sources[i], stageLabels[i]);
        batch.link(label, stages, attributes, publish);
    }

    /**
     * The published program, 0 before.
     */
    public int handle() {
        return handle;
    }

    private void release() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        } else if (handle != 0) {
            GlAsyncCompiler.retire(handle);
            handle = 0;
        }
    }
}
