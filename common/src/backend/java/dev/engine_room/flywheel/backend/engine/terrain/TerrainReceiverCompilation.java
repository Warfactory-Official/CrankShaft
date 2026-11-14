package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.lib.util.TerrainReceivers;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Receiver matching in a meshing worker's section coordinates, before any GPU geometry upload.
 */
public final class TerrainReceiverCompilation {
    public static final int[] EMPTY = new int[0];
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private TerrainReceiverCompilation() {
    }

    public static @Nullable Context begin(ClientLevel level, long section) {
        var previous = CURRENT.get();
        var faces = TerrainReceivers.snapshot(level).section(section);
        if (faces.isEmpty() && previous == null) return null;
        var context = new Context(previous, faces);
        CURRENT.set(context);
        return context;
    }

    public static @Nullable Context current() {
        return TerrainReceivers.active() ? CURRENT.get() : null;
    }

    public static final class Context implements AutoCloseable {
        private final @Nullable Context previous;
        private final TerrainReceivers.Section faces;
        private final float[] quad = new float[20];
        private final int[] scratch = new int[12];

        private Context(@Nullable Context previous, TerrainReceivers.Section faces) {
            this.previous = previous;
            this.faces = faces;
        }

        public float[] quad() {
            return quad;
        }

        public int match() {
            return faces.find(quad, scratch);
        }

        public int[] capture(ByteBuffer vertices, int stride, int positionOffset, int uvOffset) {
            if (faces.isEmpty()) return EMPTY;
            var source = vertices.duplicate().order(ByteOrder.nativeOrder());
            int count = source.remaining() / stride;
            int[] result = EMPTY;
            for (int first = 0; first < count; first += 4) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    int offset = source.position() + (first + vertex) * stride, target = vertex * 5;
                    quad[target] = source.getFloat(offset + positionOffset);
                    quad[target + 1] = source.getFloat(offset + positionOffset + 4);
                    quad[target + 2] = source.getFloat(offset + positionOffset + 8);
                    quad[target + 3] = source.getFloat(offset + uvOffset);
                    quad[target + 4] = source.getFloat(offset + uvOffset + 4);
                }
                int id = match();
                if (id == 0) continue;
                if (result.length == 0) result = new int[count];
                Arrays.fill(result, first, first + 4, id);
            }
            return result;
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
