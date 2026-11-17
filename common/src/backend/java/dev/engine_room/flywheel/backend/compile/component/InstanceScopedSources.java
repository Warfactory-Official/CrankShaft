package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Type shaders of one merged program. A file naming {@code FlwInstance}, directly or through its includes, is split by
 * top-level definition: what depends on {@code FlwInstance} or on the entry {@code function} is emitted per type under
 * that type's spellings, the rest once. A per-type definition that no {@code FlwInstance} parameter tells apart is
 * renamed per type. A whole-file include guard is dropped, its macro kept: each part is emitted once already.
 */
final class InstanceScopedSources {
    private static final String INSTANCE = "FlwInstance";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DIRECTIVE = Pattern.compile("#\\s*(\\w+)\\s*(.*)", Pattern.DOTALL);
    private static final Pattern TRAILING_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> CONDITIONALS = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif");

    private final String function;
    private final Map<SourceComponent, Split> splits = new IdentityHashMap<>();

    InstanceScopedSources(String function) {
        this.function = function;
    }

    /**
     * {@code shader}, the root shader of the type whose case is {@code index}, with every file it reaches through
     * {@code FlwInstance} scoped to that case.
     */
    SourceComponent typeShader(SourceComponent shader, Layout layout, SourceComponent struct, int index) {
        return typePart(shader, layout, struct, index, new IdentityHashMap<>());
    }

    private SourceComponent typePart(SourceComponent file, Layout layout, SourceComponent struct, int index,
                                     Map<SourceComponent, SourceComponent> parts) {
        SourceComponent part = parts.get(file);
        if (part != null) return part;
        Split split = split(file);
        var included = new ArrayList<SourceComponent>();
        included.add(struct);
        included.add(split.shared());
        for (SourceComponent include : file.included()) {
            included.add(scoped(include) ? typePart(include, layout, struct, index, parts) : include);
        }
        var source = new StringBuilder();
        var renames = new ArrayList<String>();
        renames.add(INSTANCE);
        renames.add(function);
        renames.addAll(split.renamed());
        for (String name : renames)
            source.append("#define ").append(name).append(' ').append(name).append('_')
                  .append(index).append('\n');
        source.append(InstanceStructComponent.selectRenamed(layout, split.perType()));
        for (String name : renames) source.append("#undef ").append(name).append('\n');
        part = new Part(file.name() + "#" + index, source.toString(), included);
        parts.put(file, part);
        return part;
    }

    private static boolean scoped(SourceComponent include) {
        return mentions(include.source(), Set.of(INSTANCE)) || include.included().stream()
                                                                      .anyMatch(InstanceScopedSources::scoped);
    }

    private Split split(SourceComponent file) {
        Split split = splits.get(file);
        if (split != null) return split;

        var tokens = new HashSet<>(List.of(INSTANCE, function));
        var renamed = new LinkedHashSet<String>();
        var sharedIncludes = new ArrayList<SourceComponent>();
        for (SourceComponent include : file.included()) {
            if (scoped(include)) {
                Split inner = split(include);
                tokens.addAll(inner.tokens());
                renamed.addAll(inner.renamed());
                sharedIncludes.add(inner.shared());
            } else {
                sharedIncludes.add(include);
            }
        }

        List<Chunk> chunks = chunks(file.source());
        boolean[] dependent = new boolean[chunks.size()];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                if (dependent[i] || !mentions(chunk.dependencyText(), tokens)) continue;
                dependent[i] = true;
                changed = true;
                tokens.addAll(chunk.names());
            }
        }

        int first = 0;
        int last = chunks.size();
        if (guarded(chunks)) {
            first++;
            last--;
        }
        var shared = new StringBuilder();
        var perType = new StringBuilder();
        for (int i = first; i < last; i++) {
            Chunk chunk = chunks.get(i);
            if (chunk.conditional()) {
                shared.append(chunk.text()).append('\n');
                perType.append(chunk.text()).append('\n');
            } else if (dependent[i] && !chunk.directive()) {
                perType.append(chunk.text()).append('\n');
                if (!chunk.overloadable()) renamed.addAll(chunk.names());
            } else {
                shared.append(chunk.text()).append('\n');
            }
        }
        renamed.remove(INSTANCE);
        renamed.remove(function);
        split = new Split(new Part(file.name() + "#shared", shared.toString(), sharedIncludes), perType.toString(),
                tokens, renamed);
        splits.put(file, split);
        return split;
    }

    /**
     * {@code #ifndef G} opening the file and its {@code #endif} closing it.
     */
    private static boolean guarded(List<Chunk> chunks) {
        if (chunks.size() < 2 || !chunks.getFirst().keyword().equals("ifndef")) return false;
        int depth = 0;
        for (int i = 0; i < chunks.size(); i++) {
            switch (chunks.get(i).keyword()) {
                case "if", "ifdef", "ifndef" -> depth++;
                case "endif" -> {
                    if (--depth == 0) return i == chunks.size() - 1;
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static boolean mentions(String text, Set<String> names) {
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            if (names.contains(matcher.group())) return true;
        }
        return false;
    }

    /**
     * Top-level directives and declarations of {@code source}, comments removed; a declaration ends at its semicolon,
     * or at the brace closing a function body.
     */
    static List<Chunk> chunks(String source) {
        String text = withoutComments(source.replace("\r\n", "\n"));
        var chunks = new ArrayList<Chunk>();
        var pending = new StringBuilder();
        int braces = 0;
        int parens = 0;
        boolean lineStart = true;
        boolean body = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '#' && lineStart && braces == 0) {
                int end = i;
                while (end < text.length() && (text.charAt(end) != '\n' || text.charAt(end - 1) == '\\')) end++;
                chunks.add(Chunk.directive(text.substring(i, end).strip()));
                i = end;
                continue;
            }
            pending.append(c);
            if (c == '\n') {
                lineStart = true;
                continue;
            }
            if (!Character.isWhitespace(c)) lineStart = false;
            switch (c) {
                case '(' -> parens++;
                case ')' -> parens--;
                case '{' -> {
                    if (braces++ == 0) body = pending.substring(0, pending.length() - 1).strip().endsWith(")");
                }
                case '}' -> {
                    if (--braces == 0 && body) {
                        chunks.add(Chunk.declaration(pending.toString().strip()));
                        pending.setLength(0);
                    }
                }
                case ';' -> {
                    if (braces == 0 && parens == 0) {
                        chunks.add(Chunk.declaration(pending.toString().strip()));
                        pending.setLength(0);
                    }
                }
                default -> {
                }
            }
        }
        if (!pending.isEmpty() && !pending.toString().isBlank()) {
            chunks.add(Chunk.declaration(pending.toString().strip()));
        }
        return chunks;
    }

    private static String withoutComments(String source) {
        var out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                if (i < source.length()) out.append('\n');
            } else if (c == '/' && next == '*') {
                out.append(' ');
                for (i += 2; i < source.length() && !source.startsWith("*/", i); i++) {
                    if (source.charAt(i) == '\n') out.append('\n');
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    record Chunk(String text, boolean directive, String keyword, String dependencyText, Set<String> names,
                 boolean overloadable) {
        static Chunk directive(String text) {
            Matcher matcher = DIRECTIVE.matcher(text);
            String keyword = matcher.matches() ? matcher.group(1) : "";
            String rest = matcher.matches() ? matcher.group(2) : "";
            if (!keyword.equals("define")) return new Chunk(text, true, keyword, "", Set.of(), false);
            Matcher name = IDENTIFIER.matcher(rest);
            if (!name.lookingAt()) return new Chunk(text, true, keyword, "", Set.of(), false);
            // A macro expanding to a dependent name makes its uses dependent; it stays shared and keeps its name.
            return new Chunk(text, true, keyword, rest.substring(name.end()), Set.of(name.group()), false);
        }

        static Chunk declaration(String text) {
            int open = functionParen(text);
            if (open >= 0) {
                int close = matching(text, open);
                String name = lastIdentifier(text.substring(0, open));
                boolean overloadable = mentions(text.substring(open, close), Set.of(INSTANCE));
                return new Chunk(text, false, "", text, name == null ? Set.of() : Set.of(name), overloadable);
            }
            return new Chunk(text, false, "", text, declaredNames(text), false);
        }

        boolean conditional() {
            return directive && CONDITIONALS.contains(keyword);
        }

        /**
         * The parameter list's {@code (} of a function definition or prototype, else -1.
         */
        private static int functionParen(String text) {
            if (!text.endsWith("}") && !text.endsWith(";")) return -1;
            int start = text.startsWith("layout") ? matching(text, text.indexOf('(')) + 1 : 0;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '=' || c == '{') return -1;
                if (c == '(') return lastIdentifier(text.substring(start, i)) == null ? -1 : i;
            }
            return -1;
        }

        private static int matching(String text, int open) {
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) return i;
            }
            return text.length();
        }

        private static String lastIdentifier(String text) {
            String trimmed = text.strip();
            Matcher matcher = TRAILING_IDENTIFIER.matcher(trimmed);
            return matcher.find() ? matcher.group() : null;
        }

        /**
         * Names a variable, struct or block declaration introduces: identifiers at depth 0 after a type name, a closing
         * bracket or brace, or a comma, and before an initializer, comma, semicolon, array size or body.
         */
        private static Set<String> declaredNames(String text) {
            if (text.startsWith("precision")) return Set.of();
            var names = new LinkedHashSet<String>();
            int depth = 0;
            char previous = 0;
            boolean previousIdentifier = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) continue;
                if (Character.isLetter(c) || c == '_') {
                    int end = i;
                    while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end))
                            || text.charAt(end) == '_')) end++;
                    int after = end;
                    while (after < text.length() && Character.isWhitespace(text.charAt(after))) after++;
                    char next = after < text.length() ? text.charAt(after) : ';';
                    if (depth == 0 && (previousIdentifier || previous == ']' || previous == '}' || previous == ',')
                            && "=,;[{".indexOf(next) >= 0) {
                        names.add(text.substring(i, end));
                    }
                    previousIdentifier = true;
                    i = end - 1;
                    continue;
                }
                if (c == '(' || c == '{' || c == '[') depth++;
                if (c == ')' || c == '}' || c == ']') depth--;
                previous = c;
                previousIdentifier = false;
            }
            return names;
        }
    }

    private record Split(SourceComponent shared, String perType, Set<String> tokens, Set<String> renamed) {
    }

    private record Part(String name, String source, Collection<? extends SourceComponent> included)
            implements SourceComponent {
    }
}
