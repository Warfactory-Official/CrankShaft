package dev.engine_room.flywheel.iris.compile.patches;

import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A ported pack receives raw reversed depth and projections, so every line of its program closure that names a depth
 * source, a depth output or a projection must be one the port decided. The manifest certifies each file by a
 * fingerprint of those lines after porting; a file whose depth lines changed, or a new file with depth lines, is
 * unported. The scan is textual, so it covers every option branch.
 */
final class NativeDepthAudit {
    static final String PROPERTIES = "shaders.properties";
    private static final Set<String> STAGES = Set.of("vsh", "fsh", "gsh", "csh", "tcs", "tes");
    private static final List<String> VOXY_SOURCES = List.of("voxy_opaque.glsl", "voxy_translucent.glsl",
            "voxy_taa.glsl");
    private static final Pattern DEPTH = Pattern.compile("\\b(?:depthtex[012]|gdepthtex|dhDepthTex[01]?"
            + "|vxDepthTex(?:Opaque|Trans)|shadowtex[01](?:HW)?|watershadow|gl_FragDepth|gbufferProjection(?:Inverse)?"
            + "|gbufferPreviousProjection|gl_ProjectionMatrix(?:Inverse)?|gl_ModelViewProjectionMatrix|ftransform"
            + "|dhProjection(?:Inverse)?|dhPreviousProjection|shadowProjection(?:Inverse)?|vxProj(?:Inv|Prev)?"
            + "|vxViewProj(?:Inv|Prev)?|centerDepthSmooth)\\b");
    /**
     * {@code gl_FragCoord} without a swizzle, or with one naming depth ({@code z}, {@code p}).
     */
    private static final Pattern FRAG_DEPTH = Pattern.compile("\\bgl_FragCoord\\b(?!\\s*\\.\\s*[xywstq]+\\b)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private NativeDepthAudit() {
    }

    /**
     * The first program-closure file reachable from {@code present} (staged sources first) whose depth lines differ
     * from {@code certified}, keyed by pack-relative path, or {@code null} when every one matches.
     */
    static @Nullable String unported(Path root, Set<String> present, Map<Path, String> overrides,
                                     Map<String, String> certified) {
        var pending = new ArrayDeque<AbsolutePackPath>();
        Set<String> seen = new HashSet<>();
        Set<String> directories = new HashSet<>();
        for (String path : present) {
            int dot = path.lastIndexOf('.');
            if (dot < 0 || !STAGES.contains(path.substring(dot + 1))) continue;
            pending.add(AbsolutePackPath.fromAbsolutePath(path));
            directories.add(path.substring(0, path.lastIndexOf('/') + 1));
        }
        // Voxy compiles these per program directory, outside Iris's program set.
        for (String directory : directories) {
            for (String voxy : VOXY_SOURCES) {
                var path = AbsolutePackPath.fromAbsolutePath(directory + voxy);
                if (source(root, path, overrides) != null) pending.add(path);
            }
        }
        while (!pending.isEmpty()) {
            AbsolutePackPath path = pending.poll();
            if (!seen.add(path.getPathString())) continue;
            String source = source(root, path, overrides);
            String relative = path.getPathString().substring(1);
            if (source == null) return relative + " is missing";
            String depthLines = depthLines(source);
            if (!depthLines.isEmpty() && !fingerprint(depthLines).equals(certified.get(relative))) return relative;
            Matcher include = PackPatch.INCLUDE.matcher(source);
            while (include.find()) {
                String target = include.group(1);
                pending.add(target.startsWith("/") ? AbsolutePackPath.fromAbsolutePath(target)
                        : path.parent().orElseThrow().resolve(target));
            }
        }
        String properties = source(root, AbsolutePackPath.fromAbsolutePath("/" + PROPERTIES), overrides);
        if (properties == null) return null;
        String entries = depthEntries(properties);
        return entries.isEmpty() || fingerprint(entries).equals(certified.get(PROPERTIES)) ? null : PROPERTIES;
    }

    private static @Nullable String source(Path root, AbsolutePackPath path, Map<Path, String> overrides) {
        String staged = overrides.get(path.resolved(root));
        String source = staged != null ? staged : ContractPatches.read(root, path);
        return source == null ? null : PackPatch.normalize(source);
    }

    /**
     * Each code line naming a depth token, comments removed and whitespace collapsed.
     */
    private static String depthLines(String source) {
        Matcher block = BLOCK_COMMENT.matcher(source);
        var text = new StringBuilder();
        while (block.find())
            block.appendReplacement(text, Matcher.quoteReplacement(block.group().replaceAll("[^\n]", " ")));
        block.appendTail(text);
        var lines = new ArrayList<String>();
        for (String line : text.toString().split("\n", -1)) {
            int comment = line.indexOf("//");
            String code = comment < 0 ? line : line.substring(0, comment);
            if (DEPTH.matcher(code).find() || FRAG_DEPTH.matcher(code).find()) {
                lines.add(SPACE.matcher(code.strip()).replaceAll(" "));
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Each non-comment entry (backslash continuations joined) naming a depth token.
     */
    private static String depthEntries(String properties) {
        String[] lines = properties.split("\n", -1);
        var entries = new ArrayList<String>();
        for (int start = 0; start < lines.length; ) {
            int end = start;
            while (end + 1 < lines.length && lines[end].strip().endsWith("\\")) end++;
            var entry = new StringBuilder();
            for (int index = start; index <= end; index++) {
                if (index > start) entry.append('\n');
                entry.append(SPACE.matcher(lines[index].strip()).replaceAll(" "));
            }
            if (!lines[start].strip().startsWith("#") && DEPTH.matcher(entry).find()) entries.add(entry.toString());
            start = end + 1;
        }
        return String.join("\n", entries);
    }

    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
