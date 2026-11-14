package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Fixed GL stages requested on the render thread, published by the frame compiler poll, and owned by the caller.
 */
public final class AsyncGlProgram implements AutoCloseable {
    public static final ProgramVersions.Tracker<AsyncGlProgram[]> GROUP = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(AsyncGlProgram[] programs) {
            var state = ProgramVersions.State.PUBLISHED;
            for (var program : programs) {
                var member = GlProgramBuild.TRACKER.state(program.build);
                if (member == ProgramVersions.State.ABSENT) return member;
                if (member == ProgramVersions.State.PENDING) state = member;
            }
            return state;
        }

        @Override
        public void release(AsyncGlProgram[] programs) {
            for (var program : programs) program.close();
        }
    };

    private final GlProgramBuild build;
    private @Nullable GlProgram program;

    private AsyncGlProgram(String label, ShaderType[] types, Compilation[] stages, String[] attributes,
                           Consumer<GlProgram> setup) {
        var enums = new int[types.length];
        var sources = new String[types.length];
        var labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            enums[i] = types[i].glEnum;
            labels[i] = label + "/" + types[i].name;
            sources[i] = stages[i].driverSource(types[i], labels[i]);
        }
        build = GlProgramBuild.request(label, enums, sources, labels, attributes, null, handle -> {
            var published = new GlProgram(handle);
            setup.accept(published);
            program = published;
        });
    }

    public static AsyncGlProgram graphics(String label, Compilation vertex, Compilation fragment,
                                          Consumer<GlProgram> setup) {
        return graphics(label, vertex, fragment, new String[0], setup);
    }

    public static AsyncGlProgram graphics(String label, Compilation vertex, Compilation fragment,
                                          String[] attributes, Consumer<GlProgram> setup) {
        return new AsyncGlProgram(label, new ShaderType[]{ShaderType.VERTEX, ShaderType.FRAGMENT},
                new Compilation[]{vertex, fragment}, attributes, setup);
    }

    public static AsyncGlProgram compute(String label, Compilation source, Consumer<GlProgram> setup) {
        return new AsyncGlProgram(label, new ShaderType[]{ShaderType.COMPUTE}, new Compilation[]{source}, new String[0],
                setup);
    }

    public @Nullable GlProgram get() {
        return program;
    }

    @Override
    public void close() {
        GlProgramBuild.TRACKER.release(build);
        program = null;
    }
}
