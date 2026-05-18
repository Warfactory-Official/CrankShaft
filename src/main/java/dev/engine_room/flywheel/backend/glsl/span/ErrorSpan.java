package dev.engine_room.flywheel.backend.glsl.span;

import dev.engine_room.flywheel.backend.glsl.SourceLines;

public class ErrorSpan extends Span {
    public ErrorSpan(SourceLines in, int loc) {
        super(in, loc, loc);
    }

    public ErrorSpan(SourceLines in, int start, int end) {
        super(in, start, end);
    }

    public ErrorSpan(SourceLines in, CharPos start, CharPos end) {
        super(in, start, end);
    }

    @Override
    public Span subSpan(int from, int to) {
        return new ErrorSpan(in, start, end);
    }

    @Override
    public String get() {
        return "";
    }

    @Override
    public boolean isErr() {
        return true;
    }
}
