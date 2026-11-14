package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Canonical depth of the native LoD front, owned by its Iris render targets. Render-thread calls only.
 * Sampler registration supplies a texture without a front at current target size; publication writes it as a
 * depth-tested MRT.
 */
public final class FarFrontDepth {
    /**
     * No published front. Forward canonical 0.0 is the near plane; raw reversed depth of any point in front of the
     * camera exceeds -near / (far - near), so -1.0 is unreachable where raw 0.0 is the far plane.
     */
    public static final float NONE = 0, REVERSED_NONE = -1;
    private static final Map<RenderTargets, State> STATES = new IdentityHashMap<>();
    private static final float[] FORWARD_CLEAR = {NONE}, REVERSED_CLEAR = {REVERSED_NONE};

    private FarFrontDepth() {
    }

    /**
     * Called once at the capture-frame boundary, before any host front publication. Profiles without a registered
     * consumer or publisher allocate no storage.
     * Resizing replaces the texture; sampler suppliers and host framebuffer attachment code must obtain its current ID.
     */
    public static void begin(RenderTargets targets) {
        var state = STATES.get(targets);
        if (state == null) return;
        int width = targets.getCurrentWidth();
        int height = targets.getCurrentHeight();
        if (state.width != width || state.height != height) {
            GlStateManager._deleteTexture(state.texture);
            state.texture = create(width, height);
            state.width = width;
            state.height = height;
        }
        clear(state.texture);
    }

    private static void clear(int texture) {
        GL44C.glClearTexImage(texture, 0, GL11C.GL_RED, GL11C.GL_FLOAT,
                NativeDepth.active() ? REVERSED_CLEAR : FORWARD_CLEAR);
    }

    /**
     * Registered dynamic samplers and host publication attachments consume this render target's current texture.
     */
    public static int texture(RenderTargets targets) {
        return STATES.computeIfAbsent(targets,
                target -> new State(target.getCurrentWidth(), target.getCurrentHeight())).texture;
    }

    /**
     * Called by RenderTargets.destroy(), including teardown of a partially constructed Iris pipeline.
     */
    public static void close(RenderTargets targets) {
        var state = STATES.remove(targets);
        if (state != null) GlStateManager._deleteTexture(state.texture);
    }

    private static int create(int width, int height) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, GL30C.GL_R32F, width, height);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(texture, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
        GL45C.glTextureParameteri(texture, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        return texture;
    }

    private static final class State {
        private int texture;
        private int width;
        private int height;

        private State(int width, int height) {
            this.width = width;
            this.height = height;
            texture = create(width, height);
            clear(texture);
        }
    }
}
