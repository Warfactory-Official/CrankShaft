package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-thread ownership of linked programs shared by native pipeline wrappers.
 */
public final class GlNativeProgramPool {
    private static final Map<Key, Entry> ENTRIES = new HashMap<>();

    private GlNativeProgramPool() {
    }

    public static Key key(int[] types, String[] sources, String[] attributes, List<?> uniforms,
                          List<String> samplers, ProgramAvailability.@Nullable Feature owner) {
        return new Key(GlProgramBinaryCache.digest(types, sources, attributes),
                List.copyOf(uniforms), List.copyOf(samplers), owner);
    }

    public static boolean contains(Key key) {
        return ENTRIES.containsKey(key);
    }

    public static @Nullable Entry acquire(Key key) {
        Entry entry = ENTRIES.get(key);
        if (entry != null) entry.references++;
        return entry;
    }

    public static Entry adopt(Key key, int handle) {
        Entry entry = ENTRIES.get(key);
        if (entry != null) {
            GlAsyncCompiler.retire(handle);
            entry.references++;
            return entry;
        }
        entry = new Entry(key, handle);
        ENTRIES.put(key, entry);
        return entry;
    }

    public record Key(String digest, List<?> uniforms, List<String> samplers,
                      ProgramAvailability.@Nullable Feature owner) {
    }

    public static final class Entry {
        private final Key key;
        private final int handle;
        private int references = 1;

        private Entry(Key key, int handle) {
            this.key = key;
            this.handle = handle;
        }

        public int handle() {
            return handle;
        }

        public Entry retain() {
            references++;
            return this;
        }

        public void release() {
            if (--references == 0) {
                ENTRIES.remove(key, this);
                GlStateManager.glDeleteProgram(handle);
            }
        }
    }
}
