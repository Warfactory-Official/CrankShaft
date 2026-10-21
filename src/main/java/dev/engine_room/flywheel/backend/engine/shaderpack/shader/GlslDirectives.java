package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import io.github.douira.glsl_transformer.GLSLLexer;
import io.github.douira.glsl_transformer.ast.node.Version;
import org.antlr.v4.runtime.Token;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

final class GlslDirectives {
    private GlslDirectives() { }

    static List<Directive> scan(String source) {
        var lexer = GlslSourceLexer.create(source);
        var result = new ObjectArrayList<Directive>();
        var token = lexer.nextToken();
        while (token.getType() != Token.EOF) {
            if (token.getType() != GLSLLexer.NR) { token = lexer.nextToken(); continue; }
            var start = token.getStartIndex();
            var end = token.getStopIndex() + 1;
            var kind = -1;
            var version = -1;
            token = lexer.nextToken();
            while (token.getType() != GLSLLexer.NR_EOL && token.getType() != Token.EOF) {
                end = token.getStopIndex() + 1;
                if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                    if (kind < 0) kind = token.getType();
                    else if (kind == GLSLLexer.NR_VERSION && version < 0) version = Version.fromToken(token).number;
                }
                token = lexer.nextToken();
            }
            if (token.getType() == GLSLLexer.NR_EOL) end = token.getStopIndex() + 1;
            if (kind == GLSLLexer.NR_VERSION || kind == GLSLLexer.NR_EXTENSION || kind == GLSLLexer.NR_LINE)
                result.add(new Directive(kind, source.substring(start, end), start, end, version));
            if (token.getType() != Token.EOF) token = lexer.nextToken();
        }
        return result;
    }

    record Directive(int kind, String text, int start, int end, int version) { }
}
