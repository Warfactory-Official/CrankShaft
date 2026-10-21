package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GuestShaderTemplates;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class DeferredReplayShaders {
    private DeferredReplayShaders() {
    }

    static String compatibilityHeader(String source) {
        var result = new StringBuilder(GuestShaderTemplates.HEADER);
        GuestShaderTemplates.copyExtensions(source, result);
        return result.toString();
    }

    static String resource(String name) {
        try (var stream = DeferredReplayShaders.class.getResourceAsStream(
                "/assets/flywheel/flywheel/shaderpack/replay/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static String source(String resource, Consumer<Compilation> defines) {
        var source = resource(resource);
        if (!source.startsWith("#version 430 core\n") && !source.startsWith("#version 430 core\r\n"))
            throw new IllegalArgumentException("Unexpected replay kernel version: " + resource);
        var compilation = new Compilation();
        defines.accept(compilation);
        return "#version 430 core\n" + compilation.assembledSource() + source.substring(source.indexOf('\n') + 1);
    }

    static GlProgram graphics(String fragment, Consumer<Compilation> defines) {
        return GuestPrograms.graphics(source("layer_fullscreen.vert", defines), source(fragment, defines));
    }

    static GlProgram graphics(String fragment) {
        return graphics(fragment, _ -> {
        });
    }

    static GlProgram compute(String resource, String prelude) {
        var source = source(resource, _ -> {
        });
        var offset = source.indexOf('\n') + 1;
        return GuestPrograms.compute(source.substring(0, offset) + prelude + '\n' + source.substring(offset));
    }

    static GlProgram compute(String resource, Consumer<Compilation> defines) {
        return GuestPrograms.compute(source(resource, defines));
    }
}
