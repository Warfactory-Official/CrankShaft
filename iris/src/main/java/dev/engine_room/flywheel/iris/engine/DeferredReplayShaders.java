package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import java.util.function.Consumer;

/**
 * Render-thread compiler for renderer-owned replay kernels; assembled once before linking and deleted by their owner.
 */
public final class DeferredReplayShaders {
    private DeferredReplayShaders() {
    }

    public static String source(String resource, Consumer<Compilation> defines) {
        String source = DeferredOitProfile.resource(resource);
        if (!source.startsWith("#version 430 core\n") && !source.startsWith("#version 430 core\r\n")) {
            throw new IllegalArgumentException("Unexpected replay kernel version: " + resource);
        }
        Compilation compilation = new Compilation();
        compilation.version(GlslVersion.V430);
        defines.accept(compilation);
        compilation.appendComponent(new ShaderAssembly.RawSource(resource, source.substring(source.indexOf('\n') + 1)));
        return compilation.assembledSource();
    }

    public static int graphics(String fragment) {
        return graphics(fragment, ShaderAssembly.NO_EXTRA);
    }

    public static int graphics(String fragment, Consumer<Compilation> defines) {
        return link(new int[]{GL20C.GL_VERTEX_SHADER, GL20C.GL_FRAGMENT_SHADER},
                source("layer_fullscreen.vert", defines), source(fragment, defines));
    }

    public static int compute(String resource, Consumer<Compilation> defines) {
        return compute(source(resource, defines));
    }

    static int compute(String source) {
        return link(new int[]{GL43C.GL_COMPUTE_SHADER}, source);
    }

    static int link(int[] types, String... sources) {
        int program = GlStateManager.glCreateProgram();
        int[] shaders = new int[types.length];
        int attached = 0;
        boolean linked = false;
        try {
            for (int i = 0; i < types.length; i++) {
                int shader = shaders[i] = GlStateManager.glCreateShader(types[i]);
                GL20C.glShaderSource(shader, sources[i]);
                GlStateManager.glCompileShader(shader);
                if (GlStateManager.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
                    throw new IllegalStateException("Deferred replay shader: " + GL20C.glGetShaderInfoLog(shader));
                }
                GlStateManager.glAttachShader(program, shader);
                attached++;
            }
            GlStateManager.glLinkProgram(program);
            if (GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("Deferred replay program: " + GL20C.glGetProgramInfoLog(program));
            }
            linked = true;
            return program;
        } finally {
            for (int i = 0; i < shaders.length; i++)
                if (shaders[i] != 0) {
                    if (i < attached) GL20C.glDetachShader(program, shaders[i]);
                    GlStateManager.glDeleteShader(shaders[i]);
                }
            if (!linked) GlStateManager.glDeleteProgram(program);
        }
    }
}
