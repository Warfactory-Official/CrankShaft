package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Generated GLSL text on disk under {@code cache/crankshaft/glsl/}, one file per content-addressed key: a key covers
 * the generator's code identity and every input text. GL-only; render-thread access. Vulkan generators bypass it.
 */
public final class GeneratedSourceCache {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("crankshaft.glsl.sourceCache", "true"));
    private static final long LIMIT = 256L << 20;
    private static final long TARGET = 192L << 20;
    private static @Nullable Path directory;
    private static boolean initialized;
    private static final Map<List<Class<?>>, MessageDigest> IDENTITIES = new HashMap<>();
    public static int hits;
    public static int misses;

    private GeneratedSourceCache() {
    }

    /** A digest seeded with the bytes of {@code classes}, or null when the cache is off. */
    public static @Nullable MessageDigest digest(Class<?>... classes) {
        if (!available()) return null;
        try {
            return (MessageDigest) IDENTITIES.computeIfAbsent(List.of(classes), GeneratedSourceCache::identity).clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest identity(List<Class<?>> classes) {
        MessageDigest digest = sha256();
        for (Class<?> type : classes) {
            String resource = type.getName().replace('.', '/') + ".class";
            try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("No class bytes for " + resource);
                digest.update(in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Reading " + resource, e);
            }
        }
        return digest;
    }

    public static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[]{(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8), (byte) bytes.length});
        digest.update(bytes);
    }

    public static String key(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    public static @Nullable String read(String key) {
        Path path = directory.resolve(key + ".glsl");
        if (!Files.exists(path)) {
            misses++;
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            hits++;
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("Reading generated source " + path, e);
        }
    }

    public static void write(String key, String text) {
        Path path = directory.resolve(key + ".glsl");
        try {
            Path temporary = Files.createTempFile(directory, key, ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Writing generated source " + path, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean available() {
        if (VkContext.isVulkanHost()) return false;
        if (initialized) return directory != null;
        RenderSystem.assertOnRenderThread();
        initialized = true;
        if (!ENABLED) {
            FlwBackend.LOGGER.info("Generated GLSL cache off");
            return false;
        }
        Path current = Minecraft.getInstance().gameDirectory.toPath().resolve("cache/crankshaft/glsl");
        try {
            Files.createDirectories(current);
            GlProgramBinaryCache.evict(current, LIMIT, TARGET);
        } catch (IOException e) {
            throw new UncheckedIOException("Preparing generated GLSL cache " + current, e);
        }
        directory = current;
        return true;
    }
}
