package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import org.anarres.cpp.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.UncheckedIOException;
import io.github.douira.glsl_transformer.GLSLLexer;

public final class GlslPreprocessor {

    private GlslPreprocessor() {
    }

    public static String preprocess(String source) {
        var header = new StringBuilder();
        var body = new StringBuilder(source.length());
        var version = "110";
        var previous = 0;
        for (var directive : GlslDirectives.scan(source)) {
            if (directive.kind() == GLSLLexer.NR_VERSION) version = Integer.toString(directive.version());
            if (directive.kind() != GLSLLexer.NR_LINE) {
                header.append(directive.text());
                if (!directive.text().endsWith("\n")) header.append('\n');
            }
            body.append(source, previous, directive.start()).append('\n');
            previous = directive.end();
        }
        body.append(source, previous, source.length());
        // Active framebuffer comments must survive preprocessing; the shader host has already expanded includes.
        source = body.toString();
        try (var preprocessor = new Preprocessor()) {
            preprocessor.addFeature(Feature.KEEPCOMMENTS);
            preprocessor.addMacro("__VERSION__", version);
            var extensions = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (var index = 0; index < extensions; index++) {
                preprocessor.addMacro(GL30.glGetStringi(GL11.GL_EXTENSIONS, index), "1");
            }
            preprocessor.addInput(new StringLexerSource(source, true));
            for (Token token; (token = preprocessor.token()) != null && token.getType() != Token.EOF; ) {
                header.append(token.getText());
            }
            return header.toString();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } catch (LexerException error) {
            throw new IllegalStateException("Could not preprocess guest GLSL", error);
        }
    }
}
