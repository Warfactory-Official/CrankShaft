package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MojImportPreprocessor {
    // Import texts read for keys; a warm-up follows every resource reload and clears them.
    private static final Map<String, Optional<String>> CONTENTS = new HashMap<>();
    private static final Pattern IMPORT = Pattern.compile("moj_import[^\n<\"]*[<\"]([^>\"\n]*)[>\"]");
    private static volatile @Nullable Map<Identifier, Resource> listed;

    private MojImportPreprocessor() {
    }

    /**
     * Publishes {@code ShaderManager.prepare}'s shader listing, other mods' edits included; imports resolve from the
     * last one. Called from the reload's background preparation, before any listener applies.
     */
    public static void listed(Map<Identifier, Resource> files) {
        listed = files;
    }

    public static String flatten(String source) {
        // 26.2: vanilla's #moj_import reads ShaderManager's listing, where mods patch includes; generated sources too.
        Map<Identifier, Resource> resources = listed;
        if (resources == null) throw new IllegalStateException("#moj_import flattened before ShaderManager listed shaders");
        MessageDigest digest = GeneratedSourceCache.digest(MojImportPreprocessor.class, Importer.class,
                GlslPreprocessor.class);
        if (digest == null) return preprocess(source, resources);
        GeneratedSourceCache.update(digest, SharedConstants.getCurrentVersion().id());
        GeneratedSourceCache.update(digest, source);
        // Every import target the text names, comments included: an extra one only narrows the key.
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(source));
        while (!pending.isEmpty()) {
            Matcher matcher = IMPORT.matcher(pending.pop());
            while (matcher.find()) {
                String path = matcher.group(1);
                if (!seen.add(path)) continue;
                GeneratedSourceCache.update(digest, path);
                String content = CONTENTS.computeIfAbsent(path, key -> Optional.ofNullable(content(resources, key)))
                                         .orElse(null);
                GeneratedSourceCache.update(digest, content == null ? "-" : "+" + content);
                if (content != null) pending.add(content);
            }
        }
        String key = GeneratedSourceCache.key(digest);
        String cached = GeneratedSourceCache.read(key);
        if (cached != null) return cached;
        String flattened = preprocess(source, resources);
        GeneratedSourceCache.write(key, flattened);
        return flattened;
    }

    private static @Nullable String content(Map<Identifier, Resource> resources, String path) {
        Identifier location = Identifier.tryParse(path);
        if (location == null) return null;
        Resource resource = resources.get(location.withPrefix("shaders/include/"));
        if (resource == null) return null;
        try (Reader reader = resource.openAsReader()) {
            return IOUtils.toString(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading shader import " + location, e);
        }
    }

    public static void clearImportContents() {
        CONTENTS.clear();
    }

    private static String preprocess(String source, Map<Identifier, Resource> resources) {
        return String.join("", new Importer(resources).process(source));
    }

    private static final class Importer extends GlslPreprocessor {
        private final Map<Identifier, Resource> resources;
        private final Set<Identifier> imported = new HashSet<>();

        private Importer(Map<Identifier, Resource> resources) {
            this.resources = resources;
        }

        @Override
        public @Nullable String applyImport(boolean isRelative, String path) {
            if (isRelative) {
                return "#error relative #moj_import is unsupported in generated flywheel source: " + path;
            }
            Identifier location = Identifier.parse(path)
                                            .withPrefix("shaders/include/");
            if (!imported.add(location)) {
                return null;
            }
            try (Reader reader = Objects.requireNonNull(resources.get(location))
                                        .openAsReader()) {
                return IOUtils.toString(reader);
            } catch (Exception e) {
                return "#error could not open #moj_import " + location + ": " + e.getMessage();
            }
        }
    }
}
