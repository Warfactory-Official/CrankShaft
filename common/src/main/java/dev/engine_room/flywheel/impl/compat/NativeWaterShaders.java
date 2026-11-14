package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitPass;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitShaders;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exact native water stages, asynchronously specialized for the selected OIT graph and owned with the host program.
 */
public final class NativeWaterShaders implements AutoCloseable {
    private static final OitMode[] WAVELET = {
            OitMode.DEPTH_RANGE, OitMode.GENERATE_COEFFICIENTS, OitMode.EVALUATE
    };
    private static final Int2ObjectOpenHashMap<NativeWaterShaders> LIVE = new Int2ObjectOpenHashMap<>();

    private final String label;
    private final int nativeProgram;
    private final String vertex;
    private final String fragment;
    private final String output;
    private final String[] attributes;
    private final Map<OitMode, Variant> wavelet = new EnumMap<>(OitMode.class);
    private final Map<OitInsertMode, Variant> insert = new EnumMap<>(OitInsertMode.class);
    private boolean closed;
    private @Nullable ShaderSources sources;

    public NativeWaterShaders(String label, int nativeProgram, String vertex, String fragment, String output) {
        this.label = label;
        this.nativeProgram = nativeProgram;
        this.vertex = vertex;
        this.fragment = fragment;
        this.output = output;
        attributes = attributes(nativeProgram);
        LIVE.put(nativeProgram, this);
    }

    /**
     * DH and Blaze keep their linked stage objects attached, including the host's complete preprocessing.
     */
    public static NativeWaterShaders capture(String label, int nativeProgram) {
        String vertex = null;
        String fragment = null;
        try (var stack = MemoryStack.stackPush()) {
            var stages = stack.mallocInt(GL20C.glGetProgrami(nativeProgram, GL20C.GL_ATTACHED_SHADERS));
            GL20C.glGetAttachedShaders(nativeProgram, null, stages);
            for (int i = 0; i < stages.remaining(); i++) {
                int stage = stages.get(i);
                int type = GL20C.glGetShaderi(stage, GL20C.GL_SHADER_TYPE);
                if (type == GL20C.GL_VERTEX_SHADER) vertex = GL20C.glGetShaderSource(stage);
                if (type == GL20C.GL_FRAGMENT_SHADER) fragment = GL20C.glGetShaderSource(stage);
            }
        }
        return new NativeWaterShaders(label, nativeProgram, Objects.requireNonNull(vertex),
                Objects.requireNonNull(fragment), fragmentOutput(nativeProgram));
    }

    private static String fragmentOutput(int program) {
        int outputs = GL43C.glGetProgramInterfacei(program, GL43C.GL_PROGRAM_OUTPUT, GL43C.GL_ACTIVE_RESOURCES);
        for (int i = 0; i < outputs; i++) {
            String name = GL43C.glGetProgramResourceName(program, GL43C.GL_PROGRAM_OUTPUT, i);
            if (GL43C.glGetProgramResourceLocation(program, GL43C.GL_PROGRAM_OUTPUT, name) == 0) return name;
        }
        throw new IllegalStateException("Native water program has no colour output at location zero");
    }

    public int nativeProgram() {
        return nativeProgram;
    }

    /**
     * Captured, host-preprocessed stages, valid for this native program's lifetime. Render-thread specialization.
     */
    public String vertexSource() {
        return vertex;
    }

    public String fragmentSource() {
        return fragment;
    }

    /**
     * Attribute locations for asynchronous link setup; the returned array is read-only to callers.
     */
    public String[] attributeBindings() {
        return attributes;
    }

    /**
     * Copies native sampler and uniform-block bindings once when a specialization publishes. Render thread.
     */
    public void setupBindings(GlProgram program) {
        bindings(program.handle());
    }

    public boolean closed() {
        return closed;
    }

    public boolean ready() {
        if (closed) return false;
        if (sources != FlwPrograms.SOURCES) {
            closePrograms();
            sources = FlwPrograms.SOURCES;
        }
        OitInsertMode selected = OitConfig.resolveInsertMode();
        if (selected != null) return insert.computeIfAbsent(selected, mode -> build(null, mode)).program.get() != null;
        boolean ready = true;
        for (OitMode mode : WAVELET) {
            ready &= wavelet.computeIfAbsent(mode, stage -> build(stage, null)).program.get() != null;
        }
        return ready;
    }

    public GlProgram program(RawOitPass pass) {
        return Objects.requireNonNull(variant(pass).program.get());
    }

    public int uniform(RawOitPass pass, int nativeLocation) {
        if (nativeLocation < 0) return nativeLocation;
        return variant(pass).uniforms[nativeLocation];
    }

    private Variant variant(RawOitPass pass) {
        return pass.insert() != null ? insert.get(pass.insert()) : wavelet.get(pass.wavelet());
    }

    private Variant build(@Nullable OitMode waveletMode, @Nullable OitInsertMode insertMode) {
        var vert = new Compilation();
        vert.version(ShaderAssembly.glslVersion());
        vert.appendNativeStage(new RawSource(label + "/native vertex", vertex));

        var frag = new Compilation();
        frag.version(ShaderAssembly.glslVersion());
        frag.appendComponent(new RawSource("native water entry aliases",
                "#define main _flw_nativeWaterMain\n#define " + output + " _flw_rawOut0\n"));
        frag.appendNativeStage(new RawSource(label + "/native fragment", fragment));
        frag.appendComponent(new RawSource("native water entry boundary",
                "#undef main\n#undef " + output + "\n"));
        RawOitShaders.appendProducer(frag, waveletMode, insertMode, true);
        frag.appendComponent(new RawSource("native water OIT entry", """
                void main() {
                    if (gl_HelperInvocation) discard;
                    _flw_rawOut0 = vec4(0.0);
                    _flw_nativeWaterMain();
                    vec4 shaded = _flw_rawOut0;
                    if (shaded.a <= 0.0) discard;
                    _flw_rawBegin();
                    _flw_rawLock();
                    _flw_rawEmit(shaded, _flw_rawLinearDepth(gl_FragCoord.z),
                            _flw_rawDeviceDepth(gl_FragCoord.z));
                    _flw_rawUnlock();
                }
                """));

        var result = new Variant();
        result.program = AsyncGlProgram.graphics(label + "/" + (insertMode != null ? insertMode : waveletMode),
                vert, frag, attributes, program -> result.uniforms = bindings(program.handle()));
        return result;
    }

    private int[] bindings(int program) {
        var locations = new HashMap<Integer, Integer>();
        int maxLocation = -1;
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int count = GL20C.glGetProgrami(nativeProgram, GL20C.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < count; i++) {
                String name = GL20C.glGetActiveUniform(nativeProgram, i, size, type);
                int from = GL20C.glGetUniformLocation(nativeProgram, name);
                if (from < 0) continue;
                int to = GL20C.glGetUniformLocation(program, name);
                locations.put(from, to);
                maxLocation = Math.max(maxLocation, from);
                if (type.get(0) == GL20C.GL_SAMPLER_2D && to >= 0) {
                    GL41C.glProgramUniform1i(program, to, GL20C.glGetUniformi(nativeProgram, from));
                }
            }
        }
        int blocks = GL20C.glGetProgrami(nativeProgram, GL31C.GL_ACTIVE_UNIFORM_BLOCKS);
        for (int i = 0; i < blocks; i++) {
            String name = GL31C.glGetActiveUniformBlockName(nativeProgram, i);
            int to = GL31C.glGetUniformBlockIndex(program, name);
            if (to != GL31C.GL_INVALID_INDEX) {
                GL31C.glUniformBlockBinding(program, to,
                        GL31C.glGetActiveUniformBlocki(nativeProgram, i, GL31C.GL_UNIFORM_BLOCK_BINDING));
            }
        }
        var remap = new int[maxLocation + 1];
        Arrays.fill(remap, -1);
        locations.forEach((from, to) -> remap[from] = to);
        return remap;
    }

    private static String[] attributes(int program) {
        var locations = new HashMap<Integer, String>();
        int maxLocation = -1;
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int count = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_ATTRIBUTES);
            for (int i = 0; i < count; i++) {
                String name = GL20C.glGetActiveAttrib(program, i, size, type);
                int location = GL20C.glGetAttribLocation(program, name);
                locations.put(location, name);
                maxLocation = Math.max(maxLocation, location);
            }
        }
        var result = new String[Math.max(maxLocation + 1, 0)];
        locations.forEach((location, name) -> {
            if (location >= 0) result[location] = name;
        });
        return result;
    }

    /**
     * Native pipeline-cache invalidation retires pending and published specializations before a host ID is deleted.
     */
    public static void releaseNative(int nativeProgram) {
        var shaders = LIVE.get(nativeProgram);
        if (shaders != null) shaders.close();
    }

    @Override
    public void close() {
        closed = true;
        closePrograms();
        LIVE.remove(nativeProgram, this);
    }

    private void closePrograms() {
        wavelet.values().forEach(variant -> variant.program.close());
        insert.values().forEach(variant -> variant.program.close());
        wavelet.clear();
        insert.clear();
    }

    private static final class Variant {
        private AsyncGlProgram program;
        private int[] uniforms;
    }
}
