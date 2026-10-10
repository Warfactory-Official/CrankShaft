package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.FlwBackend;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Linked GL program binaries on disk, one file per program under {@code cache/crankshaft/gl-<driver>/}, keyed by
 * every stage's type and final source plus the attribute bindings. A driver identity change selects a new directory
 * and prunes the old ones. Render thread only.
 */
public final class GlProgramBinaryCache {
    private static final int MAGIC = 0x46574250;
    private static final long LIMIT = 1L << 30;
    private static final long TARGET = 768L << 20;
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("crankshaft.gl.programCache", "true"));
    private static @Nullable Path directory;
    private static boolean initialized;
    public static int hits;
    public static int misses;
    public static int rejected;
    public static long storedBytes;

    private GlProgramBinaryCache() {
    }

    /** The program's binary key, or null when this context cannot load binaries. */
    public static @Nullable String key(int[] types, String[] sources, String[] attributes) {
        if (!available()) return null;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer integer = ByteBuffer.allocate(4);
        for (int i = 0; i < types.length; i++) {
            digest.update(integer.clear().putInt(types[i]).array());
            byte[] source = sources[i].getBytes(StandardCharsets.UTF_8);
            digest.update(integer.clear().putInt(source.length).array());
            digest.update(source);
        }
        digest.update(integer.clear().putInt(attributes.length).array());
        for (String attribute : attributes) {
            byte[] name = attribute == null ? new byte[0] : attribute.getBytes(StandardCharsets.UTF_8);
            digest.update(integer.clear().putInt(attribute == null ? -1 : name.length).array());
            digest.update(name);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Loads {@code key}'s binary into the unlinked {@code program}; false leaves it to be compiled and linked. */
    public static boolean load(String key, int program) {
        Path path = directory.resolve(key + ".bin");
        if (!Files.exists(path)) {
            misses++;
            return false;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading GL program binary " + path, e);
        }
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || header.getInt(0) != MAGIC || header.getInt(8) != bytes.length - 12) {
            reject(path);
            return false;
        }
        ByteBuffer binary = MemoryUtil.memAlloc(bytes.length - 12);
        try {
            binary.put(bytes, 12, bytes.length - 12).flip();
            GL41C.glProgramBinary(program, header.getInt(4), binary);
        } finally {
            MemoryUtil.memFree(binary);
        }
        // A driver may refuse any binary; that program then links from source.
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            reject(path);
            return false;
        }
        hits++;
        return true;
    }

    /** Before linking a program whose binary {@link #store} will read. */
    public static void retrievable(int program) {
        GL41C.glProgramParameteri(program, GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11C.GL_TRUE);
    }

    /** Persists the linked {@code program} under {@code key}. */
    public static void store(String key, int program) {
        Path path = directory.resolve(key + ".bin");
        // In-batch duplicate linked before the first stored; Windows refuses replacing a just-written file.
        if (Files.exists(path)) return;
        int length = GL20C.glGetProgrami(program, GL41C.GL_PROGRAM_BINARY_LENGTH);
        if (length <= 0) throw new IllegalStateException("Linked program reports no binary: " + key);
        ByteBuffer binary = MemoryUtil.memAlloc(length);
        int[] format = new int[1];
        try {
            GL41C.glGetProgramBinary(program, null, format, binary);
            byte[] bytes = new byte[12 + length];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).putInt(format[0]).putInt(length);
            binary.get(bytes, 12, length);
            Path temporary = Files.createTempFile(directory, key, ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            storedBytes += bytes.length;
        } catch (IOException e) {
            throw new UncheckedIOException("Writing GL program binary " + key, e);
        } finally {
            MemoryUtil.memFree(binary);
        }
    }

    private static void reject(Path path) {
        rejected++;
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Deleting rejected GL program binary " + path, e);
        }
    }

    // every input change adds entries: past limit the oldest-written go until target remains
    static void evict(Path current, long limit, long target) throws IOException {
        List<Path> binaries = new ArrayList<>();
        try (Stream<Path> entries = Files.list(current)) {
            for (Path path : entries.toList()) {
                if (path.getFileName().toString().endsWith(".tmp")) Files.delete(path);
                else binaries.add(path);
            }
        }
        long total = 0;
        for (Path path : binaries) total += Files.size(path);
        if (total <= limit) return;
        Map<Path, FileTime> written = new HashMap<>();
        for (Path path : binaries) written.put(path, Files.getLastModifiedTime(path));
        binaries.sort(Comparator.comparing(written::get));
        long before = total;
        for (Path path : binaries) {
            if (total <= target) break;
            total -= Files.size(path);
            Files.delete(path);
        }
        FlwBackend.LOGGER.info("Evicted cache entries in {}: {} -> {} MiB", current, before >> 20, total >> 20);
    }

    private static boolean available() {
        if (initialized) return directory != null;
        RenderSystem.assertOnRenderThread();
        initialized = true;
        GLCapabilities caps = GL.getCapabilities();
        if (!ENABLED || caps.glProgramBinary == MemoryUtil.NULL || caps.glGetProgramBinary == MemoryUtil.NULL
                || caps.glProgramParameteri == MemoryUtil.NULL
                || GL11C.glGetInteger(GL41C.GL_NUM_PROGRAM_BINARY_FORMATS) <= 0) {
            FlwBackend.LOGGER.info("GL program binary cache off (enabled={})", ENABLED);
            return false;
        }
        String driver = GL11C.glGetString(GL11C.GL_VENDOR) + '\n' + GL11C.glGetString(GL11C.GL_RENDERER) + '\n'
                + GL11C.glGetString(GL11C.GL_VERSION) + '\n' + GL11C.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION);
        String identity;
        try {
            identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(driver.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("cache/crankshaft");
        Path current = root.resolve("gl-" + identity);
        try {
            Files.createDirectories(current);
            try (Stream<Path> siblings = Files.list(root)) {
                for (Path stale : siblings.filter(path -> path.getFileName().toString().startsWith("gl-")
                        && !path.equals(current)).toList()) {
                    try (Stream<Path> tree = Files.walk(stale)) {
                        for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
                    }
                    FlwBackend.LOGGER.info("Pruned GL program binaries of another driver: {}", stale);
                }
            }
            evict(current, LIMIT, TARGET);
        } catch (IOException e) {
            throw new UncheckedIOException("Preparing GL program binary cache " + current, e);
        }
        directory = current;
        FlwBackend.LOGGER.info("GL program binary cache {}", current);
        return true;
    }
}
