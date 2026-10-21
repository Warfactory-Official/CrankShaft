package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.FlwBackend;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public final class ContractPatches {
    private ContractPatches() {
    }

    static Plan prepare(GuestShaderPack pack) throws IOException {
        var originals = GuestPackPatches.snapshot(pack);
        var recipe = GuestPackPatches.select(pack);
        if (recipe == null) {
            var contract = new Properties();
            var contractSource = pack.read("colorwheel.properties");
            if (contractSource != null) contract.load(new StringReader(contractSource));
            // An authored material program can explicitly disable OIT and still require native material replay.
            var authored = Boolean.parseBoolean(contract.getProperty("oit", "true")) && pack.paths().stream().anyMatch(
                    path -> path.equals("clrwl_gbuffers_translucent.fsh")
                            || path.endsWith("/clrwl_gbuffers_translucent.fsh"));
            var replay = DeferredOitProfile.select(pack, authored);
            if (replay != null) return DeferredReplayCompilation.stage(pack, originals, replay);
            return new Plan(Map.copyOf(originals), pack.prefix(), false);
        }
        var changes = new LinkedHashMap<String, String>();
        var present = new HashSet<>(pack.paths());
        try {
            for (var entry : recipe.getAsJsonArray("files")) {
                var edit = entry.getAsJsonObject();
                var target = target(edit.get("target").getAsString());
                var directories = directories(present, target.base);
                if (directories.isEmpty()) continue;
                applyDiff(pack, changes, edit.get("patch").getAsString());
                for (var directory : directories) {
                    if (present.contains(directory + target.program + ".fsh")) continue;
                    for (var extension : List.of(".vsh", ".fsh")) {
                        var source = read(pack, changes, directory + target.nativeProgram + extension);
                        if (source == null) throw new UnsupportedOperationException(
                                "Missing native wrapper " + directory + target.nativeProgram);
                        changes.put(directory + target.program + extension, define(source, target.define));
                    }
                }
            }
            if (recipe.has("patches"))
                for (var diff : recipe.getAsJsonArray("patches")) applyDiff(pack, changes, diff.getAsString());
            var nativeProperties = new StringBuilder();
            for (var entry : recipe.getAsJsonArray("programs")) {
                var program = entry.getAsJsonObject();
                var target = program.get("target").getAsString();
                var vertex = program.has("vertex") ? program.get("vertex").getAsString() : "clrwl_gbuffers";
                var fragment = GuestPackPatches.resource(program.get("fragment").getAsString());
                var nativeDraw = program.has("nativeDraw") && program.get("nativeDraw").getAsBoolean();
                var directories = directories(present, vertex);
                for (var directory : directories) {
                    var source = read(pack, changes, directory + vertex + ".vsh");
                    if (source == null)
                        throw new UnsupportedOperationException("Missing patch vertex " + directory + vertex);
                    changes.put(directory + target + ".vsh", source);
                    changes.put(directory + target + ".fsh",
                            nativeDraw ? define(fragment, "CRANKSHAFT_NATIVE_ADDITIVE") : fragment);
                    if (nativeDraw)
                        changes.put(directory + vertex + ".fsh", define(fragment, "CRANKSHAFT_NATIVE_ADDITIVE"));
                }
                if (nativeDraw && !directories.isEmpty())
                    nativeProperties.append("\nblend.").append(vertex).append("=ONE ONE ZERO ONE\nalphaTest.")
                                    .append(vertex).append("=off\n");
            }
            changes.put("colorwheel.properties",
                    value(pack.read("colorwheel.properties")) + "\n" + recipe.get("properties").getAsString());
            if (!nativeProperties.isEmpty())
                changes.put("shaders.properties", value(pack.read("shaders.properties")) + nativeProperties);
            FlwBackend.LOGGER.info("Colorwheel context-checked patch applied: {} ({} sources)",
                    recipe.get("name").getAsString(), changes.size());
            originals.putAll(changes);
            return new Plan(Map.copyOf(originals), pack.prefix(),
                    recipe.has("emissiveLight") && recipe.get("emissiveLight").getAsBoolean());
        } catch (UnsupportedOperationException error) {
            FlwBackend.LOGGER.warn("Colorwheel patch {} rejected as a whole: {}", recipe.get("name").getAsString(),
                    error.getMessage());
            return new Plan(Map.copyOf(originals), pack.prefix(), false);
        }
    }

    private static String value(String source) {
        return source == null ? "" : source;
    }

    private static Target target(String name) {
        return switch (name) {
            case "clrwl_gbuffers_entities" ->
                    new Target(name, "gbuffers_entities", "clrwl_gbuffers", "CRANKSHAFT_CLRWL_ENTITIES");
            case "clrwl_gbuffers_block" ->
                    new Target(name, "gbuffers_block", "clrwl_gbuffers", "CRANKSHAFT_CLRWL_BLOCK");
            default -> throw new IllegalStateException("Unsupported bundled Colorwheel target " + name);
        };
    }

    private static List<String> directories(Set<String> paths, String program) {
        var filename = program + ".fsh";
        return paths.stream().filter(path -> path.substring(path.lastIndexOf('/') + 1).equals(filename))
                    .map(path -> path.substring(0, path.length() - filename.length())).sorted().toList();
    }

    private static String read(GuestShaderPack pack, Map<String, String> changes, String path) throws IOException {
        return changes.containsKey(path) ? changes.get(path) : pack.read(path);
    }

    private static void applyDiff(GuestShaderPack pack, Map<String, String> changes, String name) throws IOException {
        var patch = UnifiedPatch.parse(GuestPackPatches.resource(name));
        for (var file : patch.files()) {
            var source = read(pack, changes, file.path());
            var result = source == null ? null : file.apply(source);
            if (result == null)
                throw new UnsupportedOperationException("Context changed for " + name + ": " + file.path());
            changes.put(file.path(), result);
        }
    }

    private static String define(String source, String name) {
        var line = source.startsWith("#version") ? source.indexOf('\n') + 1 : 0;
        return source.substring(0, line) + "#define " + name + "\n" + source.substring(line);
    }

    private record Target(String program, String nativeProgram, String base, String define) {
    }

    public record Plan(Map<String, String> resources, String prefix, boolean emissiveLight, String replayProfile) {
        public Plan(Map<String, String> resources, String prefix, boolean emissiveLight) {
            this(resources, prefix, emissiveLight, null);
        }
    }
}
