package dev.engine_room.flywheel.backend.glsl;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.backend.glsl.span.Span;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Import(Span self, Span file) {
    public static final Pattern PATTERN = Pattern.compile("^\\s*#\\s*include\\s+\"(.*)\"", Pattern.MULTILINE);

    public static ImmutableList<Import> parseImports(SourceLines source) {
        Matcher matcher = PATTERN.matcher(source);

        var imports = ImmutableList.<Import>builder();

        while (matcher.find()) {
            Span use = Span.fromMatcher(source, matcher);
            Span file = Span.fromMatcher(source, matcher, 1);

            imports.add(new Import(use, file));
        }

        return imports.build();
    }
}
