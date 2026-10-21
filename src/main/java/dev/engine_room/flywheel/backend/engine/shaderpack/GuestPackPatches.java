package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

final class GuestPackPatches {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\h*#\\h*include\\h+\"([^\"]+)\"");
    private static final List<JsonObject> RECIPES = List.of("complementary", "bsl").stream()
                                                        .map(name -> JsonParser.parseString(resource(name + ".json"))
                                                                               .getAsJsonObject()).toList();

    private GuestPackPatches() {
    }

    static List<String> directories(Map<String, String> sources, String program) {
        var filename = program + ".fsh";
        return sources.keySet().stream().filter(path -> path.equals(filename) || path.endsWith('/' + filename))
                      .map(path -> path.substring(0, path.length() - filename.length())).sorted().toList();
    }

    static String define(String source, String name) {
        var first = source.startsWith("#version") ? source.indexOf('\n') + 1 : 0;
        return source.substring(0, first) + "#define " + name + "\n" + source.substring(first);
    }

    static String resource(String name) {
        try (var stream = GuestPackPatches.class.getResourceAsStream("/assets/flywheel/shaderpack/patches/" + name)) {
            return GuestShaderPack.normalize(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static JsonObject select(GuestShaderPack pack) throws IOException {
        return select(pack, RECIPES);
    }

    static JsonObject select(GuestShaderPack pack, List<JsonObject> recipes) throws IOException {
        JsonObject result = null;
        for (var recipe : recipes) {
            var matches = true;
            for (var match : recipe.getAsJsonArray("matches")) {
                var check = match.getAsJsonObject();
                var source = pack.read(check.get("path").getAsString());
                if (source == null) {
                    matches = false;
                    break;
                }
                for (var required : check.getAsJsonArray("contains")) {
                    if (!source.contains(required.getAsString())) matches = false;
                }
                if (check.has("excludes")) for (var excluded : check.getAsJsonArray("excludes")) {
                    if (source.contains(excluded.getAsString())) matches = false;
                }
            }
            if (!matches) continue;
            if (result != null) throw new IllegalStateException("Ambiguous Colorwheel patch family");
            result = recipe;
        }
        return result;
    }

    static Map<String, String> snapshot(GuestShaderPack pack) throws IOException {
        var sources = new HashSet<String>();
        for (var path : pack.paths()) {
            if (path.matches(".*\\.(?:glsl|vsh|fsh|gsh|tcs|tes|csh|vert|frag|comp|properties|inc|h)$"))
                dependencies(pack, path, sources, false);
        }
        var result = new HashMap<String, String>();
        for (var path : sources) result.put(path, pack.read(path));
        return result;
    }

    static Forward forwardOit(GuestShaderPack pack) throws IOException {
        var recipe = select(pack);
        if (recipe == null || !recipe.has("forwardOit")) return null;
        var guard = recipe.getAsJsonObject("forwardOit");
        if (!accepts(pack, guard)) return null;
        var frontmost = new HashSet<Integer>();
        for (var buffer : guard.getAsJsonArray("frontmost")) frontmost.add(buffer.getAsInt());
        return new Forward(Set.copyOf(frontmost));
    }

    static boolean accepts(GuestShaderPack pack, JsonObject guard) throws IOException {
        var programs = new HashSet<String>();
        for (var program : guard.getAsJsonArray("programs")) programs.add(program.getAsString());
        var sources = new HashSet<String>();
        for (var path : pack.paths()) {
            var filename = path.substring(path.lastIndexOf('/') + 1);
            var dot = filename.lastIndexOf('.');
            if (dot > 0 && programs.contains(filename.substring(0, dot))) dependencies(pack, path, sources);
        }
        for (var path : guard.getAsJsonArray("paths")) dependencies(pack, path.getAsString(), sources);
        if (sources.isEmpty()) return false;
        var fingerprint = fingerprint(pack, sources);
        for (var expected : guard.getAsJsonArray("fingerprints")) {
            if (expected.getAsString().equals(fingerprint)) {
                return true;
            }
        }
        return false;
    }

    private static void dependencies(GuestShaderPack pack, String path, Set<String> sources) throws IOException {
        dependencies(pack, path, sources, true);
    }

    private static void dependencies(GuestShaderPack pack, String path, Set<String> sources,
                                     boolean required) throws IOException {
        path = Path.of(path).normalize().toString().replace('\\', '/');
        if (!sources.add(path)) return;
        var source = pack.read(path);
        if (source == null) {
            if (required) throw new IOException("Missing shader include: " + path);
            sources.remove(path);
            return;
        }
        var includes = INCLUDE.matcher(source);
        while (includes.find()) {
            var include = includes.group(1);
            var parent = Path.of(path).getParent();
            var dependency = include.startsWith("/") ? include.substring(1)
                    : parent == null ? include : parent.resolve(include).toString();
            dependencies(pack, dependency, sources, required);
        }
    }

    private static String fingerprint(GuestShaderPack pack, Set<String> sources) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
        var paths = new ArrayList<>(sources);
        paths.sort(String::compareTo);
        for (var path : paths) {
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            var continued = false;
            for (var line : pack.read(path).split("\n")) {
                var stripped = line.strip();
                var nextContinued = stripped.endsWith("\\");
                if (!continued && !nextContinued && stripped.isEmpty()) continue;
                digest.update((continued || nextContinued ? line : stripped).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                continued = nextContinued;
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    record Forward(Set<Integer> frontmost) {
    }
}
