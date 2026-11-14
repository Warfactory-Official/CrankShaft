package dev.engine_room.flywheel.backend.compile.core;

import dev.engine_room.flywheel.backend.compile.GlAsyncCompiler;
import dev.engine_room.flywheel.backend.gl.GlObject;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;

import java.util.HashMap;
import java.util.Map;

public class CompilationHarness<K> {
    private static final String[] NO_ATTRIBUTES = new String[0];

    private final ShaderSources sources;
    private final KeyCompiler<K> compiler;
    private final ShaderCache shaderCache;
    private final ProgramLinker programLinker;

    private final Map<K, GlProgram> programs = new HashMap<>();
    private final Map<K, GlAsyncCompiler.Pending> pending = new HashMap<>();

    public CompilationHarness(String _, ShaderSources sources, KeyCompiler<K> compiler) {
        this.sources = sources;
        this.compiler = compiler;
        shaderCache = new ShaderCache();
        programLinker = new ProgramLinker();
    }

    public GlProgram get(K key) {
        GlAsyncCompiler.Pending building = pending.remove(key);
        if (building != null) building.cancel();
        return programs.computeIfAbsent(key, this::compile);
    }

    /**
     * Compiles in the background where the driver compiles in parallel, published by {@link GlAsyncCompiler#poll};
     * otherwise as {@link #get}.
     */
    public void request(K key) {
        if (programs.containsKey(key) || pending.containsKey(key)) return;
        if (!GlAsyncCompiler.available()) {
            get(key);
            return;
        }
        Stages stages = compiler.stages(key, sources);
        GlProgram[] linking = new GlProgram[1];
        pending.put(key, GlAsyncCompiler.submit(key.toString(), stages.types(), stages.sources(), stages.labels(),
                NO_ATTRIBUTES, null, handle -> {
                    linking[0] = new GlProgram(handle);
                    compiler.preLink(key, linking[0]);
                }, handle -> {
                    pending.remove(key);
                    GlProgram program = linking[0] != null ? linking[0] : new GlProgram(handle);
                    compiler.postLink(key, program);
                    programs.put(key, program);
                }, () -> pending.remove(key)));
    }

    public boolean ready(K key) {
        return programs.containsKey(key);
    }

    public boolean pending(K key) {
        return pending.containsKey(key);
    }

    /**
     * A superseded program, never bound again.
     */
    public void release(K key) {
        GlAsyncCompiler.Pending building = pending.remove(key);
        if (building != null) {
            building.cancel();
            return;
        }
        GlProgram program = programs.remove(key);
        if (program != null) program.delete();
    }

    private GlProgram compile(K key) {
        return compiler.compile(key, sources, shaderCache, programLinker);
    }

    public void delete() {
        pending.values()
               .forEach(GlAsyncCompiler.Pending::cancel);
        pending.clear();

        shaderCache.delete();

        programs.values()
                .forEach(GlObject::delete);

        programs.clear();
    }

    public interface KeyCompiler<K> {
        GlProgram compile(K key, ShaderSources loader, ShaderCache shaderCache, ProgramLinker programLinker);

        /**
         * The driver text of every stage {@link #compile} links.
         */
        Stages stages(K key, ShaderSources loader);

        void preLink(K key, GlProgram program);

        void postLink(K key, GlProgram program);
    }

    public record Stages(int[] types, String[] sources, String[] labels) {
    }
}
