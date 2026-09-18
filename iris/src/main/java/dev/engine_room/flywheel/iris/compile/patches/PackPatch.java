package dev.engine_room.flywheel.iris.compile.patches;

import com.google.gson.Gson;
import dev.engine_room.flywheel.backend.FlwBackend;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Family manifests select candidates; diff context and capability checks establish applicability.
 */
record PackPatch(int schema, String name, List<String> testedVersions, List<SourceMatch> matches, List<FileEdit> files,
                 List<Wrapper> wrappers, List<BlendCopy> blends, String properties, @Nullable SourceGuard forwardOit,
                 @Nullable DeferredAdapter deferred) {
    private static final String RESOURCE_ROOT = "/assets/flywheel/iris/patches/";
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\h*#\\h*include\\h+\"([^\"]+)\"");
    private static final List<PackPatch> PATCHES = List.of("complementary", "solas", "iteration", "sundial", "bsl",
                                                               "makeup")
                                                       .stream().map(PackPatch::load).toList();

    private static PackPatch load(String id) {
        try (var reader = new InputStreamReader(PackPatch.class.getResourceAsStream(RESOURCE_ROOT + id + ".json"),
                StandardCharsets.UTF_8)) {
            PackPatch patch = new Gson().fromJson(reader, PackPatch.class);
            if (patch.schema != 2 || patch.testedVersions.isEmpty() || patch.matches.isEmpty()) {
                throw new IllegalStateException("Invalid bundled shaderpack patch: " + id);
            }
            for (FileEdit edit : patch.files) edit.changes();
            if (patch.forwardOit != null) validate(patch.forwardOit);
            if (patch.deferred != null) {
                DeferredOitProfile.valueOf(patch.deferred.profile);
                validate(patch.deferred.sources);
            }
            return patch;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void validate(SourceGuard guard) {
        if (guard.programs.isEmpty() && guard.paths.isEmpty() || guard.fingerprints.isEmpty()
                || guard.fingerprints.stream().anyMatch(hash -> !hash.matches("[0-9a-f]{64}"))) {
            throw new IllegalStateException("Invalid bundled shaderpack capability check");
        }
    }

    static @Nullable PackPatch select(Path root) {
        try {
            PackPatch selected = null;
            for (PackPatch patch : PATCHES) {
                boolean matches = true;
                for (SourceMatch match : patch.matches) {
                    if (!match.matches(root)) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) continue;
                if (selected != null) {
                    FlwBackend.LOGGER.warn("Ambiguous shaderpack family; trying shared contracts");
                    return null;
                }
                selected = patch;
            }
            if (selected != null) FlwBackend.LOGGER.info("Shaderpack patch family matched: {}", selected.name);
            else FlwBackend.LOGGER.info("No shaderpack patch family matched; trying shared contracts");
            return selected;
        } catch (IOException e) {
            FlwBackend.LOGGER.warn("Cannot identify shaderpack family; trying shared contracts", e);
            return null;
        }
    }

    private static void dependencies(Path root, String path, Set<String> sources) throws IOException {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) throw new IOException("Shader include escapes pack root: " + path);
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (!sources.add(relative)) return;
        var includes = INCLUDE.matcher(normalize(Files.readString(file)));
        while (includes.find()) {
            String include = includes.group(1);
            Path dependency = include.startsWith("/") ? root.resolve(include.substring(1)) : file.getParent()
                                                                                                 .resolve(include);
            dependencies(root, root.relativize(dependency).toString(), sources);
        }
    }

    static String fingerprint(Path root, Set<String> sources) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        List<String> paths = new ArrayList<>(sources);
        paths.sort(String::compareTo);
        for (String path : paths) {
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            // Preserve continuations: removing a blank line after '\\' can extend a macro or line comment.
            String source = normalize(Files.readString(root.resolve(path)));
            boolean continued = false;
            for (String line : source.split("\n")) {
                String stripped = line.strip();
                boolean nextContinued = stripped.endsWith("\\");
                if (!continued && !nextContinued && stripped.isEmpty()) continue;
                digest.update((continued || nextContinued ? line : stripped).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                continued = nextContinued;
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String normalize(String text) {
        return (text.startsWith("\uFEFF") ? text.substring(1) : text).replace("\r\n", "\n");
    }

    record SourceMatch(String path, List<String> contains) {
        boolean matches(Path root) throws IOException {
            Path file = root.resolve(path);
            if (!Files.isRegularFile(file)) return false;
            String source = Files.readString(file);
            return contains.stream().allMatch(source::contains);
        }
    }

    record FileEdit(String target, String patch) {
        UnifiedPatch changes() {
            try (var stream = PackPatch.class.getResourceAsStream(RESOURCE_ROOT + patch)) {
                return UnifiedPatch.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    record DeferredAdapter(String profile, SourceGuard sources) {
    }

    /**
     * Checks only the program dependency closure whose material semantics were certified.
     */
    record SourceGuard(List<String> programs, List<String> paths, List<String> fingerprints) {
        boolean accepts(Path root, Set<String> starts) {
            try {
                Set<String> sources = new HashSet<>();
                for (String path : paths) dependencies(root, path, sources);
                for (String path : starts) {
                    String filename = path.substring(path.lastIndexOf('/') + 1);
                    int dot = filename.lastIndexOf('.');
                    if (dot > 0 && programs.contains(filename.substring(0, dot))) {
                        dependencies(root, path.substring(1), sources);
                    }
                }
                if (sources.isEmpty()) return false;
                return fingerprints.contains(fingerprint(root, sources));
            } catch (IOException e) {
                FlwBackend.LOGGER.warn("Cannot check shaderpack capability sources; preserving native rendering", e);
                return false;
            }
        }
    }

    record Wrapper(String target, String vertexContract, String fragmentContract, String vertexNative,
                   String fragmentNative, boolean useContract, String marker) {
    }

    record BlendCopy(String target, String nativeProgram, boolean global) {
    }
}
