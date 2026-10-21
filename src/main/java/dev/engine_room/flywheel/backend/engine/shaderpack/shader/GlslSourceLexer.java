package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import io.github.douira.glsl_transformer.GLSLLexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.Pair;

final class GlslSourceLexer {
    private GlslSourceLexer() { }

    static GLSLLexer create(String source) {
        var lexer = new GLSLLexer(new Source(source));
        lexer.enableAllFlags();
        // Prescans consume each token before asking for the next one. Never use this factory with the AST parser.
        lexer.setTokenFactory(new Tokens());
        return lexer;
    }

    private static final class Source implements CharStream {
        private final String text;
        private int cursor;

        Source(String text) { this.text = text; }
        @Override public void consume() {
            if (cursor == text.length()) throw new IllegalStateException("Cannot consume EOF");
            cursor++;
        }
        @Override public int LA(int offset) {
            if (offset == 0) return 0;
            var index = cursor + (offset < 0 ? offset : offset - 1);
            return index < 0 || index >= text.length() ? IntStream.EOF : text.charAt(index);
        }
        @Override public int mark() { return -1; }
        @Override public void release(int marker) { }
        @Override public int index() { return cursor; }
        @Override public void seek(int index) { cursor = index; }
        @Override public int size() { return text.length(); }
        @Override public String getSourceName() { return IntStream.UNKNOWN_SOURCE_NAME; }
        @Override public String getText(Interval interval) {
            return text.substring(interval.a, Math.min(text.length(), interval.b + 1));
        }
    }

    private static final class Tokens implements TokenFactory<CommonToken> {
        private static final Pair<TokenSource, CharStream> EMPTY = new Pair<>(null, null);
        private final MutableToken token = new MutableToken();

        @Override public CommonToken create(Pair<TokenSource, CharStream> source, int type, String text, int channel,
                                           int start, int stop, int line, int column) {
            token.reset(source, type, text, channel, start, stop, line, column);
            return token;
        }

        @Override public CommonToken create(int type, String text) {
            return create(EMPTY, type, text, 0, -1, -1, 0, -1);
        }
    }

    private static final class MutableToken extends CommonToken {
        MutableToken() { super(0); }

        void reset(Pair<TokenSource, CharStream> source, int type, String text, int channel, int start, int stop,
                   int line, int column) {
            this.source = source;
            this.type = type;
            this.text = text;
            this.channel = channel;
            this.start = start;
            this.stop = stop;
            this.line = line;
            charPositionInLine = column;
            index = -1;
        }
    }
}
