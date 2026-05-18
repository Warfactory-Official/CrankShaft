package dev.engine_room.flywheel.backend.glsl.span;

import dev.engine_room.flywheel.backend.glsl.SourceLines;

import java.util.regex.Matcher;

public abstract class Span implements CharSequence, Comparable<Span> {
    protected final SourceLines in;
    protected final CharPos start;
    protected final CharPos end;

    public Span(SourceLines in, int start, int end) {
        this(in, in.getCharPos(start), in.getCharPos(end));
    }

    public Span(SourceLines in, CharPos start, CharPos end) {
        this.in = in;
        this.start = start;
        this.end = end;
    }

    public SourceLines source() {
        return in;
    }

    public CharPos start() {
        return start;
    }

    public CharPos end() {
        return end;
    }

    public int startIndex() {
        return start.pos();
    }

    public int endIndex() {
        return end.pos();
    }

    public boolean isEmpty() {
        return start == end;
    }

    public int lines() {
        return end.line() - start.line() + 1;
    }

    public int firstLine() {
        return start.line();
    }

    public abstract Span subSpan(int from, int to);

    public abstract String get();

    public abstract boolean isErr();

    @Override
    public int length() {
        return endIndex() - startIndex();
    }

    @Override
    public char charAt(int index) {
        return in.charAt(start.pos() + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return subSpan(start, end);
    }

    @Override
    public String toString() {
        return get();
    }

    public static Span fromMatcher(SourceLines src, Matcher m, int group) {
        return new StringSpan(src, m.start(group), m.end(group));
    }

    public static Span fromMatcher(Span superSpan, Matcher m, int group) {
        return superSpan.subSpan(m.start(group), m.end(group));
    }

    public static Span fromMatcher(SourceLines src, Matcher m) {
        return new StringSpan(src, m.start(), m.end());
    }

    public static Span fromMatcher(Span superSpan, Matcher m) {
        return superSpan.subSpan(m.start(), m.end());
    }

    @Override
    public int compareTo(Span o) {
        return Integer.compareUnsigned(startIndex(), o.startIndex());
    }
}
