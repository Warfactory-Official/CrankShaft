package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.api.pipeline.fbo.Attachment;
import com.luna.ausm.impl.pipeline.PipelineContext;
import com.luna.ausm.impl.pipeline.fbo.DeferredFramebuffer;
import com.luna.ausm.impl.pipeline.resource.ShaderStorageBufferSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;

import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Map;

final class AusmReplayTargets implements DeferredReplayTargets, DeferredReplayBuffers {
    private final DeferredFramebuffer framebuffer;
    private final PipelineContext context;
    private final ShaderStorageBufferSet storage;
    private final Map<Attachment, int[]> banks;
    private final Map<Attachment, Boolean> flips;
    private final IntSet frameAlt = new IntOpenHashSet();
    private final Int2ObjectOpenHashMap<Target> targets = new Int2ObjectOpenHashMap<>();

    AusmReplayTargets(PipelineContext context) {
        this.context = context;
        framebuffer = context.pingPongManager.getReadBuffer();
        storage = context.shaderStorageBuffers;
        banks = framebuffer.colorTextures;
        flips = framebuffer.flippedTextures;
    }

    boolean matches(PipelineContext context) {
        return framebuffer == context.pingPongManager.getReadBuffer()
                && storage == context.shaderStorageBuffers;
    }

    @Override
    public int width() {
        return framebuffer.getWidth();
    }

    @Override
    public int height() {
        return framebuffer.getHeight();
    }

    @Override
    public int depth() {
        return framebuffer.getDepthTexture();
    }

    @Override
    public int opaqueDepth() {
        return framebuffer.getDepthSamplerTexture(0);
    }

    @Override
    public int image(String name) {
        return context.shaderImages.textureId(name);
    }

    @Override
    public int frame() {
        return (int) context.pipelineFrameId;
    }

    @Override
    public int buffer(int binding) {
        return storage.glBufferId(binding);
    }

    @Override
    public void replace(int binding, int handle) {
        storage.glBuffers.put(binding, handle);
    }

    @Override
    public Target get(int index) {
        var target = targets.get(index);
        if (target == null) {
            target = new Bank(banks.get(Attachment.fromColorIndex(index)));
            targets.put(index, target);
        }
        return target;
    }

    IntSet readAltScratch() {
        frameAlt.clear();
        for (var entry : flips.entrySet()) if (entry.getValue()) frameAlt.add(entry.getKey().getIndex());
        return frameAlt;
    }

    void readAlt(IntSet expected) {
        for (var attachment : banks.keySet()) flips.put(attachment, expected.contains(attachment.getIndex()));
    }

    int framebuffer() {
        return framebuffer.getFramebufferId();
    }

    private record Bank(int[] textures) implements Target {
        @Override
        public int main() {
            return textures[0];
        }

        @Override
        public int alt() {
            return textures[1];
        }

        @Override
        public int format() {
            return GL45.glGetTextureLevelParameteri(main(), 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        }
    }
}
