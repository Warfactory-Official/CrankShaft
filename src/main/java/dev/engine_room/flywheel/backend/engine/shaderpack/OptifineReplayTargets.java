package dev.engine_room.flywheel.backend.engine.shaderpack;

import net.optifine.shaders.Shaders;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

final class OptifineReplayTargets implements DeferredReplayTargets, DeferredReplayBuffers, AutoCloseable {
    private static final int COLOR_TARGETS = 8;
    private final Target[] targets = new Target[COLOR_TARGETS];
    private final int[] buffers = new int[3];
    private int width, height;

    OptifineReplayTargets() {
        for (var index = 0; index < targets.length; index++) targets[index] = new Bank(index);
        try {
            buffers[1] = allocate(2L << 20);
            buffers[2] = allocate(16);
            resize();
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private static int allocate(long bytes) {
        var handle = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(handle, bytes, 0);
        return handle;
    }

    boolean resize() {
        var w = Shaders.renderWidth;
        var h = Shaders.renderHeight;
        if (width == w && height == h) return false;
        if (w <= 0 || h <= 0) throw new IllegalStateException("OptiFine replay targets are not initialized");
        var replacement = allocate((long) w * h * 4);
        if (buffers[0] != 0) GL15.glDeleteBuffers(buffers[0]);
        buffers[0] = replacement;
        width = w;
        height = h;
        return true;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int depth() {
        return Shaders.dfbDepthTextures.get(0);
    }

    @Override
    public int opaqueDepth() {
        return Shaders.dfbDepthTextures.get(1);
    }

    @Override
    public int buffer(int binding) {
        return buffers[binding];
    }

    @Override
    public void replace(int binding, int handle) {
        buffers[binding] = handle;
    }

    @Override
    public Target get(int index) {
        if (index >= COLOR_TARGETS)
            throw new UnsupportedOperationException("OptiFine 1.12 exposes only eight colour targets");
        return targets[index];
    }

    void readAlt(IntSet target) {
        target.clear();
        var flips = Shaders.dfbColorTexturesFlip.flips;
        for (var index = 0; index < flips.length; index++) if (flips[index]) target.add(index);
    }

    void applyReadAlt(IntSet expected) {
        var nativeFlips = Shaders.dfbColorTexturesFlip;
        var flips = nativeFlips.flips;
        for (var index = 0; index < flips.length; index++)
            if (flips[index] != expected.contains(index)) nativeFlips.flip(index);
    }

    @Override
    public void close() {
        for (var buffer : buffers) if (buffer != 0) GL15.glDeleteBuffers(buffer);
    }

    private record Bank(int index) implements Target {
        @Override
        public int main() {
            return Shaders.dfbColorTextures.get(index);
        }

        @Override
        public int alt() {
            return Shaders.dfbColorTextures.get(index + COLOR_TARGETS);
        }

        @Override
        public int format() {
            return GL45.glGetTextureLevelParameteri(main(), 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        }
    }
}
