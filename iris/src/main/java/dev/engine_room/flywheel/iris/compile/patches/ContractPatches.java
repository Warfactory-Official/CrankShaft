package dev.engine_room.flywheel.iris.compile.patches;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.backend.FlwBackend;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * In-memory contract adapters. Context-checked patches commit atomically; shared composition is the fallback.
 */
public final class ContractPatches {
    public static final String NATIVE_TRANSLUCENT = "_flw_nativeTranslucent";
    public static final String NATIVE_SHADOW_TRANSLUCENT = "_flw_nativeShadowTranslucent";
    private static final String CONTRACT = "clrwl_gbuffers";
    private static final List<String> CONTRACT_MARKERS = List.of("COLORWHEEL", "CLRWL");
    private static final List<String> OTHER_PROGRAMS = List.of("TERRAIN", "WATER", "BLOCK", "HAND", "SHADOW", "GLINT",
            "DAMAGED", "PARTICLE", "WEATHER", "SKY");
    private static final Target ENTITIES = new Target("clrwl_gbuffers_entities", "gbuffers_entities",
            "CRANKSHAFT_CLRWL_ENTITIES");
    private static final Target TRANSLUCENT = new Target("clrwl_gbuffers_translucent", "gbuffers_water",
            "CRANKSHAFT_CLRWL_TRANSLUCENT");
    private static final Target SHADOW_TRANSLUCENT = new Target("clrwl_shadow_translucent", "shadow_water",
            "CRANKSHAFT_CLRWL_SHADOW_TRANSLUCENT");
    private static final Target ADDITIVE = new Target("clrwl_gbuffers_additive", "gbuffers_beaconbeam",
            "CRANKSHAFT_CLRWL_ADDITIVE");
    private static final Target BLOCK = new Target("clrwl_gbuffers_block", "gbuffers_block", "CRANKSHAFT_CLRWL_BLOCK");
    private static final List<Target> TARGETS = List.of(ENTITIES, TRANSLUCENT, SHADOW_TRANSLUCENT, ADDITIVE, BLOCK);
    private static final ThreadLocal<Map<Path, String>> OVERRIDES = new ThreadLocal<>();

    private ContractPatches() {
    }

    /**
     * Plans one include graph and activates its overrides until {@link #end()}. Render thread only.
     */
    public static Plan begin(Path root, ImmutableList<AbsolutePackPath> starts) {
        long startNs = System.nanoTime();
        Map<Path, String> overrides = new HashMap<>();
        List<AbsolutePackPath> added = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (AbsolutePackPath start : starts) present.add(start.getPathString());
        String colorwheel = read(root, AbsolutePackPath.fromAbsolutePath("/colorwheel.properties"));
        if (colorwheel == null) colorwheel = "";
        PackPatch recipe = PackPatch.select(root);
        boolean forwardOit = false;
        boolean deferredEmissive = false;
        boolean deferredTranslucent = false;
        boolean emissiveLight = false;
        DeferredOitProfile deferred = null;
        String shadersProperties = null;
        if (recipe != null) {
            String patchedProperties = stageRecipe(root, recipe, present, overrides, added, colorwheel);
            if (patchedProperties != null) {
                colorwheel = patchedProperties;
                deferredEmissive = recipe.deferredEmissive();
                deferredTranslucent = recipe.deferredTranslucent();
                emissiveLight = recipe.emissiveLight();
                forwardOit = recipe.forwardOit() != null && recipe.forwardOit().accepts(root, present);
                if (recipe.forwardOit() != null && !forwardOit) {
                    FlwBackend.LOGGER.info("Shaderpack {} HDR-alpha sources changed; using shared/native eligibility",
                            recipe.name());
                }
                if (recipe.deferred() != null && DeferredOitProfile.enabled()
                        && recipe.deferred().sources().accepts(root, present)) {
                    deferred = DeferredOitProfile.valueOf(recipe.deferred().profile());
                    for (String path : present) {
                        if (!path.endsWith("/gbuffers_water.fsh")) continue;
                        String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
                        AbsolutePackPath clear = AbsolutePackPath.fromAbsolutePath(dir + "deferred98.csh");
                        if (present.contains(clear.getPathString()))
                            throw new IllegalStateException("Deferred clear program is occupied");
                        overrides.put(clear.resolved(root), DeferredOitProfile.resource("layer_clear.comp"));
                        added.add(clear);
                        if (present.contains(dir + "composite.fsh")) continue;
                        AbsolutePackPath sort = AbsolutePackPath.fromAbsolutePath(dir + "composite.csh");
                        if (present.contains(sort.getPathString()))
                            throw new IllegalStateException("Deferred sort program is occupied");
                        overrides.put(sort.resolved(root), DeferredOitProfile.sortCompute());
                        added.add(sort);
                    }
                    shadersProperties = read(root, AbsolutePackPath.fromAbsolutePath("/shaders.properties")) + """

                            iris.features.optional = SSBO
                            bufferObject.0 = 4 true 1.0 1.0
                            bufferObject.1 = 2097152
                            bufferObject.2 = 16
                            bufferObject.3 = 4 true 0.25 0.25
                            """;
                }
                if (recipe.deferred() != null && DeferredOitProfile.enabled() && deferred == null) {
                    FlwBackend.LOGGER.info(
                            "Shaderpack {} deferred material sources changed; preserving native translucency",
                            recipe.name());
                }
                FlwBackend.LOGGER.info("Shaderpack context-checked patch applied: {}", recipe.name());
            } else {
                overrides.clear();
                added.clear();
                FlwBackend.LOGGER.warn("Shaderpack patch {} rejected as a whole; trying shared contracts",
                        recipe.name());
            }
        }
        for (AbsolutePackPath path : added) present.add(path.getPathString());
        for (Target target : List.of(ENTITIES, TRANSLUCENT)) {
            for (String dir : contractDirs(present, target)) {
                if (stageWrappers(root, dir, target, overrides, added, ContractPatches::compose)) {
                    FlwBackend.LOGGER.info("Shaderpack shared contract composed: {}{} <- {}", dir,
                            target.virtualProgram, target.nativeProgram);
                }
            }
        }
        for (AbsolutePackPath path : added) present.add(path.getPathString());
        // The native bridge needs no authored Colorwheel base. Its OIT eligibility is checked after preprocessing.
        for (String path : present) {
            if (!path.endsWith("/gbuffers_water.fsh")) continue;
            String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            if (present.contains(dir + TRANSLUCENT.virtualProgram + ".fsh")) continue;
            if (!present.contains(dir + "gbuffers_water.vsh")) continue;
            nativeWrapper(root, dir, "gbuffers_water", TRANSLUCENT.virtualProgram, NATIVE_TRANSLUCENT, overrides,
                    added);
            if (!present.contains(dir + SHADOW_TRANSLUCENT.virtualProgram + ".fsh")) {
                String shadow = present.contains(dir + "shadow_water.fsh") ? "shadow_water" : "shadow";
                if (present.contains(dir + shadow + ".vsh") && present.contains(dir + shadow + ".fsh")) {
                    nativeWrapper(root, dir, shadow, SHADOW_TRANSLUCENT.virtualProgram, NATIVE_SHADOW_TRANSLUCENT,
                            overrides, added);
                }
            }
        }
        if (!overrides.isEmpty()) OVERRIDES.set(overrides);
        FlwBackend.LOGGER.info("Shaderpack patch planning: {} sources in {} ms", overrides.size(),
                (System.nanoTime() - startNs) / 1_000_000.0);
        return new Plan(ImmutableList.<AbsolutePackPath>builder().addAll(starts).addAll(added).build(), colorwheel,
                forwardOit, deferred, deferredEmissive, deferredTranslucent, emissiveLight, shadersProperties);
    }

    private static void nativeWrapper(Path root, String dir, String nativeProgram, String virtualProgram, String marker,
                                      Map<Path, String> overrides, List<AbsolutePackPath> added) {
        for (String stage : List.of(".vsh", ".fsh", ".gsh", ".tcs", ".tes")) {
            String source = read(root, AbsolutePackPath.fromAbsolutePath(dir + nativeProgram + stage));
            if (source == null) continue;
            AbsolutePackPath virtual = AbsolutePackPath.fromAbsolutePath(dir + virtualProgram + stage);
            overrides.put(virtual.resolved(root), source + "\nvoid " + marker + "() {}\n");
            added.add(virtual);
        }
    }

    private static @Nullable String stageRecipe(Path root, PackPatch recipe, Set<String> present,
                                                Map<Path, String> overrides, List<AbsolutePackPath> added,
                                                String colorwheel) {
        for (PackPatch.FileEdit edit : recipe.files()) {
            Target target = target(edit.target());
            List<String> dirs = contractDirs(present, target);
            if (dirs.isEmpty()) continue;
            if (!applyDiff(root, edit.changes(), overrides)) return null;
            for (String dir : dirs) {
                if (!stageWrappers(root, dir, target, overrides, added,
                        (contract, nativeWrapper) -> withDefines(nativeWrapper, List.of(target.define)))) return null;
            }
        }
        for (String diff : recipe.patches()) {
            if (!applyDiff(root, UnifiedPatch.parse(PackPatch.resource(diff)), overrides)) return null;
        }
        for (PackPatch.Program program : recipe.programs()) {
            Target target = target(program.target());
            String fragment = PackPatch.resource(program.fragment());
            for (String dir : contractDirs(present, target)) {
                String vertex = read(root, AbsolutePackPath.fromAbsolutePath(dir + CONTRACT + ".vsh"));
                if (vertex == null) return null;
                AbsolutePackPath vsh = AbsolutePackPath.fromAbsolutePath(dir + target.virtualProgram + ".vsh");
                AbsolutePackPath fsh = AbsolutePackPath.fromAbsolutePath(dir + target.virtualProgram + ".fsh");
                overrides.put(vsh.resolved(root), vertex);
                overrides.put(fsh.resolved(root), fragment);
                added.add(vsh);
                added.add(fsh);
            }
        }
        for (PackPatch.Wrapper wrapper : recipe.wrappers()) {
            Target target = target(wrapper.target());
            for (String dir : contractDirs(present, target)) {
                if (!stageWrappers(root, dir, target, overrides, added,
                        (contract, nativeWrapper) -> adaptWrapper(wrapper, contract, nativeWrapper))) return null;
            }
        }
        Set<String> staged = new HashSet<>(present);
        for (AbsolutePackPath path : added) staged.add(path.getPathString());
        String packProperties = read(root, AbsolutePackPath.fromAbsolutePath("/shaders.properties"));
        for (PackPatch.BlendCopy blend : recipe.blends()) {
            if (staged.stream().noneMatch(path -> path.endsWith("/" + blend.target() + ".fsh"))) return null;
            if (packProperties == null) return null;
            String copied = copyBlend(packProperties, blend);
            if (copied == null) return null;
            colorwheel += copied;
        }
        return colorwheel + '\n' + recipe.properties();
    }

    private static boolean applyDiff(Path root, UnifiedPatch diff, Map<Path, String> overrides) {
        for (UnifiedPatch.FileEdit change : diff.files()) {
            AbsolutePackPath path = AbsolutePackPath.fromAbsolutePath("/" + change.path());
            String source = overrides.get(path.resolved(root));
            if (source == null) source = read(root, path);
            String patched = source == null ? null : change.apply(source);
            if (patched == null) return false;
            overrides.put(path.resolved(root), patched);
        }
        return true;
    }

    private static @Nullable String adaptWrapper(PackPatch.Wrapper wrapper, String contract, String nativeWrapper) {
        List<String> includes = directives(contract, "#include");
        String stage = includes.equals(List.of("\"" + wrapper.vertexContract() + "\"")) ? wrapper.vertexNative()
                : includes.equals(List.of("\"" + wrapper.fragmentContract() + "\"")) ? wrapper.fragmentNative() : null;
        if (stage == null || !directives(nativeWrapper, "#include").equals(List.of("\"" + stage + "\""))) return null;
        return (wrapper.useContract() ? contract : nativeWrapper) + "\nvoid " + wrapper.marker() + "() {}\n";
    }

    private static @Nullable String copyBlend(String properties, PackPatch.BlendCopy blend) {
        String prefix = "blend." + blend.nativeProgram();
        StringBuilder copied = new StringBuilder("\n");
        int count = 0;
        for (String line : PackPatch.normalize(properties).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                copied.append(line).append('\n');
            } else if (trimmed.startsWith(prefix + ".colortex")
                    || blend.global() && trimmed.matches(Pattern.quote(prefix) + "\\s*=.*")) {
                copied.append("blend.").append(blend.target()).append(trimmed.substring(prefix.length())).append('\n');
                count++;
            }
        }
        return count == 0 ? null : copied.toString();
    }

    private static Target target(String name) {
        return TARGETS.stream().filter(target -> target.virtualProgram.equals(name)).findFirst()
                      .orElseThrow(() -> new IllegalStateException("Unknown bundled contract target " + name));
    }

    public static @Nullable String override(Path path) {
        Map<Path, String> overrides = OVERRIDES.get();
        return overrides == null ? null : overrides.get(path);
    }

    public static void end() {
        OVERRIDES.remove();
    }

    /**
     * Stages both stages of one directory's virtual program, all or nothing.
     */
    private static boolean stageWrappers(Path root, String dir, Target target, Map<Path, String> overrides,
                                         List<AbsolutePackPath> added, Build build) {
        Map<Path, String> staged = new HashMap<>();
        List<AbsolutePackPath> stagedPaths = new ArrayList<>();
        String nativeProgram = target.nativeProgram;
        if (target == SHADOW_TRANSLUCENT && (read(root,
                AbsolutePackPath.fromAbsolutePath(dir + nativeProgram + ".vsh")) == null
                || read(root, AbsolutePackPath.fromAbsolutePath(dir + nativeProgram + ".fsh")) == null)) {
            nativeProgram = "shadow";
        }
        for (String stage : List.of(".vsh", ".fsh")) {
            String contract = read(root, AbsolutePackPath.fromAbsolutePath(dir + base(target) + stage));
            String source = read(root, AbsolutePackPath.fromAbsolutePath(dir + nativeProgram + stage));
            String result = contract == null || source == null ? null : build.apply(contract, source);
            if (result == null) {
                return false;
            }
            AbsolutePackPath virtual = AbsolutePackPath.fromAbsolutePath(dir + target.virtualProgram + stage);
            staged.put(virtual.resolved(root), result);
            stagedPaths.add(virtual);
        }
        overrides.putAll(staged);
        added.addAll(stagedPaths);
        return true;
    }

    /**
     * Directories of a base contract whose native program needs {@code target}'s contract program.
     */
    private static List<String> contractDirs(Set<String> present, Target target) {
        List<String> dirs = new ArrayList<>();
        String base = base(target);
        for (String start : present) {
            if (!start.endsWith("/" + base + ".fsh")) {
                continue;
            }
            String dir = start.substring(0, start.length() - (base + ".fsh").length());
            String nativeProgram = target.nativeProgram;
            if (target == SHADOW_TRANSLUCENT && (!present.contains(dir + nativeProgram + ".vsh")
                    || !present.contains(dir + nativeProgram + ".fsh"))) nativeProgram = "shadow";
            if (!present.contains(dir + target.virtualProgram + ".fsh")
                    && present.contains(dir + nativeProgram + ".vsh")
                    && present.contains(dir + nativeProgram + ".fsh")) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    private static String base(Target target) {
        return target == SHADOW_TRANSLUCENT ? "clrwl_shadow" : CONTRACT;
    }

    /**
     * The native wrapper plus the contract wrapper's Colorwheel defines, or {@code null} unless both wrappers include
     * the same files. A define naming another program (the pack's contract-as-terrain variant) is left out.
     */
    private static @Nullable String compose(String contract, String nativeWrapper) {
        List<String> includes = directives(contract, "#include");
        if (includes.isEmpty() || !includes.equals(directives(nativeWrapper, "#include"))) {
            return null;
        }
        List<String> defines = new ArrayList<>(directives(contract, "#define"));
        defines.removeAll(directives(nativeWrapper, "#define"));
        defines.removeIf(define -> CONTRACT_MARKERS.stream()
                                                   .noneMatch(define::contains) || OTHER_PROGRAMS.stream()
                                                                                                 .anyMatch(
                                                                                                         define::contains));
        if (defines.isEmpty()) {
            return null;
        }
        return withDefines(nativeWrapper, defines);
    }

    private static List<String> directives(String wrapper, String directive) {
        List<String> found = new ArrayList<>();
        for (String line : wrapper.replace("\r\n", "\n")
                                  .split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(directive)) {
                found.add(trimmed.substring(directive.length())
                                 .trim());
            }
        }
        return found;
    }

    private static String withDefines(String wrapper, List<String> names) {
        String text = wrapper.replace("\r\n", "\n");
        int version = text.startsWith("#version") ? 0 : text.indexOf("\n#version");
        int lineEnd = version < 0 ? -1 : text.indexOf('\n', version + 1);
        StringBuilder defines = new StringBuilder();
        for (String name : names) {
            defines.append("#define ")
                   .append(name)
                   .append('\n');
        }
        return lineEnd < 0 ? defines + text : text.substring(0, lineEnd + 1) + defines + text.substring(lineEnd + 1);
    }

    private static @Nullable String read(Path root, AbsolutePackPath path) {
        try {
            return Files.readString(path.resolved(root));
        } catch (IOException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface Build {
        /**
         * The virtual program's wrapper from the pack's {@code clrwl_gbuffers} and native wrappers, or {@code null}.
         */
        @Nullable String apply(String contract, String nativeWrapper);
    }

    /**
     * A contract program to build ({@code virtualProgram}) out of the pack's own {@code nativeProgram}.
     */
    private record Target(String virtualProgram, String nativeProgram, String define) {
    }

    public record Plan(ImmutableList<AbsolutePackPath> starts, String properties, boolean forwardOit,
                       @Nullable DeferredOitProfile deferred, boolean deferredEmissive,
                       boolean deferredTranslucent, boolean emissiveLight, @Nullable String shadersProperties) {
    }
}
