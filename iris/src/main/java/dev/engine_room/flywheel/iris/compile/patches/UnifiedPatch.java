package dev.engine_room.flywheel.iris.compile.patches;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text-only git diffs. Context may move, but must match completely and uniquely.
 */
record UnifiedPatch(List<FileEdit> files) {
    private static final Pattern HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    static UnifiedPatch parse(String source) {
        String[] lines = PackPatch.normalize(source).split("\n", -1);
        List<FileEdit> files = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (lines[i].isEmpty() && i == lines.length - 1) break;
            if (!lines[i++].startsWith("diff --git a/")) throw invalid();
            if (i < lines.length && lines[i].startsWith("index ")) i++;
            if (i + 1 >= lines.length || !lines[i].startsWith("--- a/") || !lines[i + 1].startsWith("+++ b/")) {
                throw invalid();
            }
            String path = lines[i++].substring(6);
            if (!lines[i++].substring(6).equals(path) || path.isEmpty() || path.startsWith("/")
                    || path.contains("\\") || path.contains(":")
                    || List.of(path.split("/")).stream().anyMatch(part -> part.equals("..") || part.equals("."))) {
                throw invalid();
            }
            List<Hunk> hunks = new ArrayList<>();
            while (i < lines.length && lines[i].startsWith("@@ ")) {
                var header = HEADER.matcher(lines[i++]);
                if (!header.matches()) throw invalid();
                int beforeCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
                int afterCount = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
                StringBuilder before = new StringBuilder();
                StringBuilder after = new StringBuilder();
                int oldLines = 0;
                int newLines = 0;
                char previous = 0;
                while (i < lines.length) {
                    String line = lines[i];
                    if (line.equals("\\ No newline at end of file")) {
                        if (previous == ' ' || previous == '-') before.setLength(before.length() - 1);
                        if (previous == ' ' || previous == '+') after.setLength(after.length() - 1);
                        if (previous == 0) throw invalid();
                        previous = 0;
                        i++;
                        continue;
                    }
                    if (oldLines == beforeCount && newLines == afterCount) break;
                    if (line.isEmpty()) throw invalid();
                    previous = line.charAt(0);
                    if (previous == ' ' || previous == '-') {
                        before.append(line.substring(1)).append('\n');
                        oldLines++;
                    }
                    if (previous == ' ' || previous == '+') {
                        after.append(line.substring(1)).append('\n');
                        newLines++;
                    }
                    if (previous != ' ' && previous != '-' && previous != '+'
                            || oldLines > beforeCount || newLines > afterCount) throw invalid();
                    i++;
                }
                // A context-free insertion cannot establish a unique, version-tolerant location.
                if (oldLines != beforeCount || newLines != afterCount || before.isEmpty()) throw invalid();
                hunks.add(new Hunk(before.toString(), after.toString()));
            }
            if (hunks.isEmpty() || files.stream().anyMatch(file -> file.path.equals(path))) throw invalid();
            files.add(new FileEdit(path, List.copyOf(hunks)));
        }
        if (files.isEmpty()) throw invalid();
        return new UnifiedPatch(List.copyOf(files));
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Malformed or unsupported bundled shaderpack diff");
    }

    record Hunk(String before, String after) {
    }

    record FileEdit(String path, List<Hunk> hunks) {
        @Nullable String apply(String source) {
            source = PackPatch.normalize(source);
            StringBuilder result = new StringBuilder();
            int end = 0;
            for (Hunk hunk : hunks) {
                int offset = source.indexOf(hunk.before);
                if (offset < end || source.indexOf(hunk.before, offset + 1) >= 0
                        || offset > 0 && source.charAt(offset - 1) != '\n') return null;
                result.append(source, end, offset).append(hunk.after);
                end = offset + hunk.before.length();
            }
            return result.append(source, end, source.length()).toString();
        }
    }
}
