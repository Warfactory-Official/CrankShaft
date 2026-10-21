package dev.engine_room.flywheel.backend.engine.shaderpack;

import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

final class GuestShaderPack implements AutoCloseable {
    private final Path root;
    private final ZipFile zip;
    private final String name;
    private final String prefix;
    private final boolean snapshot;
    private final Map<String, String> sources = new HashMap<>();

    GuestShaderPack() throws IOException {
        this(GuestHost.current().packName());
    }

    GuestShaderPack(String name) throws IOException {
        snapshot = false;
        var directory = Minecraft.getMinecraft().gameDir.toPath().resolve("shaderpacks").toAbsolutePath().normalize();
        this.name = name;
        root = directory.resolve(name).normalize();
        if (!root.startsWith(directory)) throw new IOException("Shader pack escapes shaderpacks directory");
        zip = Files.isDirectory(root) ? null : new ZipFile(root.toFile());
        prefix = (zip == null ? Files.isDirectory(root.resolve("shaders"))
                : zip.stream().anyMatch(entry -> entry.getName().startsWith("shaders/"))) ? "shaders/" : "";
    }

    GuestShaderPack(ContractPatches.Plan plan) {
        this(GuestHost.current().packName(), plan);
    }

    GuestShaderPack(String name, ContractPatches.Plan plan) {
        root = null;
        zip = null;
        snapshot = true;
        this.name = name;
        prefix = plan.prefix();
        overlay(plan.resources());
    }

    static String normalize(String source) {
        return (source.startsWith("\uFEFF") ? source.substring(1) : source).replace("\r\n", "\n");
    }

    String read(String relative) throws IOException {
        return readResource(prefix + relative);
    }

    private String readResource(String relative) throws IOException {
        var normalized = Path.of(relative).normalize();
        if (normalized.isAbsolute() || normalized.startsWith(".."))
            throw new IOException("Shader include escapes pack: " + relative);
        var name = normalized.toString().replace('\\', '/');
        if (sources.containsKey(name)) return sources.get(name);
        if (snapshot) return null;
        String source;
        if (zip == null) {
            var file = root.resolve(name);
            source = Files.isRegularFile(file) ? normalize(Files.readString(file)) : null;
        } else {
            var entry = zip.getEntry(name);
            if (entry == null) source = null;
            else try (var stream = zip.getInputStream(entry)) {
                source = normalize(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        sources.put(name, source);
        return source;
    }

    List<String> paths() throws IOException {
        var all = new LinkedHashSet<String>();
        if (snapshot) {
            sources.keySet().forEach(path -> all.add(path.substring(prefix.length())));
        } else if (zip != null) {
            zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
               .map(entry -> entry.getName().substring(prefix.length())).forEach(all::add);
        } else {
            var shaders = root.resolve(prefix);
            try (var paths = Files.walk(shaders)) {
                paths.filter(Files::isRegularFile).map(path -> shaders.relativize(path).toString().replace('\\', '/'))
                     .forEach(all::add);
            }
        }
        sources.forEach((path, source) -> {
            if (source != null && path.startsWith(prefix)) all.add(path.substring(prefix.length()));
        });
        return List.copyOf(all);
    }

    void overlay(Map<String, String> changes) {
        changes.forEach((path, source) -> sources.put(prefix + path, normalize(source)));
    }

    String prefix() {
        return prefix;
    }

    Properties contract() throws IOException {
        return GuestHost.current().contractProperties(this);
    }

    public String getName() {
        return name;
    }

    public InputStream getResourceAsStream(String path) throws IOException {
        var source = readResource(path);
        return source == null ? null : new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    public boolean hasResource(String path) {
        try {
            return readResource(path) != null;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public void close() throws IOException {
        if (zip != null) zip.close();
    }
}
