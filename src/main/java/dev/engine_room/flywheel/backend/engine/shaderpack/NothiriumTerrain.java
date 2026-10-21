package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.impl.pipeline.PipelineContext;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.fml.common.Loader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL20;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrays;

import java.util.Arrays;

public final class NothiriumTerrain {
    private static Scope current;
    private static final ObjectArrayList<Scope> scopes = new ObjectArrayList<>();
    private static int depth;
    private static long bufferEpoch;

    private NothiriumTerrain() { }

    public static boolean enabled() {
        return hostActive() && GuestRendering.ownsTranslucentChunks();
    }

    public static boolean hostActive() { return Loader.isModLoaded("nothirium") && GuestRenderPass.active(); }

    public static Scope begin() {
        if (depth == scopes.size()) scopes.add(new Scope());
        var scope = scopes.get(depth++);
        scope.previous = current;
        scope.layer = null;
        scope.captured = false;
        scope.fallbackId = scope.renderType = 0;
        current = scope;
        return scope;
    }

    public static void layer(BlockRenderLayer layer) {
        layer(layer, 0, (short) 0);
    }

    public static void layer(BlockRenderLayer layer, int fallbackId, short renderType) {
        if (current == null) return;
        current.layer = layer == BlockRenderLayer.TRANSLUCENT && GuestRendering.ownsTranslucentChunks()
                && (!GuestRenderPass.shadowActive() || GuestRendering.replaysShadowTerrain())
                && !PipelineContext.getInstance().isBloomTranslucentAttenuationPass() ? layer : null;
        if (capturing()) {
            current.captured = true;
            current.fallbackId = fallbackId & 0xFFFF;
            current.renderType = renderType;
        }
    }

    static void bindFallback(GlProgram program) {
        var location = program.getUniformLocation("_flw_terrainFallbackMaterial");
        if (location >= 0) GL20.glUniform2i(location, captured() ? current.fallbackId : 0, captured() ? current.renderType : 0);
    }

    public static boolean capturing() {
        return current != null && current.layer == BlockRenderLayer.TRANSLUCENT;
    }

    public static boolean captured() {
        return current != null && current.captured;
    }

    static Scope slices() { return current; }

    public static void capture(Object part, int primitive, int first, int count, int buffer, int stride,
                               int x, int y, int z) {
        if ((primitive != GL11.GL_QUADS && primitive != GL40.GL_PATCHES) || count % 4 != 0
                || primitive == GL40.GL_PATCHES && GL11.glGetInteger(GL40.GL_PATCH_VERTICES) != 4
                || (stride != 28 && stride != 56))
            throw new UnsupportedOperationException("Native Nothirium terrain layout changed");
        current.add((NativeTerrainBuffer) part, buffer, first, count, stride, x, y, z);
    }

    public static void retireBuffers() {
        // DynamicVBO.dispose() deletes its buffer without invalidating the outstanding part objects.
        bufferEpoch++;
    }

    static long bufferEpoch() { return bufferEpoch; }

    public static final class Scope implements AutoCloseable {
        private static final int STRIDE = 7;
        private Scope previous;
        private NativeTerrainBuffer[] owners = new NativeTerrainBuffer[16];
        private int[] draws = new int[16 * STRIDE];
        private int size;
        private BlockRenderLayer layer;
        private boolean captured;
        private int fallbackId, renderType;

        private Scope() { }

        public boolean captured() { return captured; }
        boolean isEmpty() { return size == 0; }
        int size() { return size; }
        NativeTerrainBuffer owner(int index) { return owners[index]; }
        int buffer(int index) { return draws[index * STRIDE]; }
        long offset(int index) { return (long) draws[index * STRIDE + 1] * stride(index); }
        int count(int index) { return draws[index * STRIDE + 2]; }
        int stride(int index) { return draws[index * STRIDE + 3]; }
        int x(int index) { return draws[index * STRIDE + 4]; }
        int y(int index) { return draws[index * STRIDE + 5]; }
        int z(int index) { return draws[index * STRIDE + 6]; }

        private void add(NativeTerrainBuffer owner, int buffer, int first, int count, int stride, int x, int y, int z) {
            if (size == owners.length) {
                owners = ObjectArrays.grow(owners, size + 1);
                draws = IntArrays.grow(draws, owners.length * STRIDE);
            }
            owners[size] = owner;
            var base = size++ * STRIDE;
            draws[base] = buffer;
            draws[base + 1] = first;
            draws[base + 2] = count;
            draws[base + 3] = stride;
            draws[base + 4] = x;
            draws[base + 5] = y;
            draws[base + 6] = z;
        }

        void clear() {
            Arrays.fill(owners, 0, size, null);
            size = 0;
        }

        @Override public void close() {
            clear();
            current = previous;
            previous = null;
            depth--;
        }
    }
}
