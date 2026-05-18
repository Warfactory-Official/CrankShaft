package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.OitPrograms;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.*;

public class OitFramebuffer {
    public static final float[] CLEAR_TO_ZERO = {0, 0, 0, 0};
    public static final int[] DEPTH_RANGE_DRAW_BUFFERS = {GL30.GL_COLOR_ATTACHMENT0};
    public static final int[] RENDER_TRANSMITTANCE_DRAW_BUFFERS = {GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT2, GL30.GL_COLOR_ATTACHMENT3, GL30.GL_COLOR_ATTACHMENT4};
    public static final int[] ACCUMULATE_DRAW_BUFFERS = {GL30.GL_COLOR_ATTACHMENT5};
    public static final int[] DEPTH_ONLY_DRAW_BUFFERS = {};

    private final OitPrograms programs;
    private final int vao;

    public int fbo = -1;
    public int depthBounds = -1;
    public int coefficients = -1;
    public int accumulate = -1;

    private int lastWidth = -1;
    private int lastHeight = -1;
    private static final long BYTES_PER_PIXEL = 8L + 8L * 4L + 8L;
    private long trackedBytes;

    public OitFramebuffer(OitPrograms programs) {
        this.programs = programs;
        if (GlCompat.SUPPORTS_DSA) {
            vao = GL45.glCreateVertexArrays();
        } else {
            vao = GL30.glGenVertexArrays();
        }
    }

    /**
     * Set up the framebuffer.
     */
    public void prepare() {
        // 1.12.2 compat profile keeps fixed-function GL_ALPHA_TEST active. Vanilla world
        // rendering sets glAlphaFunc(GL_GREATER, 0.1) for cutout terrain and never disables
        // it before our render-entities TAIL inject runs. The OIT composite shader outputs
        // `frag.a = 1 - total_transmittance`, which for low-alpha translucents (e.g. radfog,
        // 0.125 peak) lands well below 0.1 — so alpha test discards every fragment and the
        // composite "wrote no fragments". Disable for the OIT block; composite() re-enables.
        GlStateManager.disableAlpha();

        Framebuffer renderTarget = Minecraft.getMinecraft().getFramebuffer();

        maybeResizeFBO(renderTarget.framebufferTextureWidth, renderTarget.framebufferTextureHeight);

        Samplers.COEFFICIENTS.makeActive();
        GlStateManager.bindTexture(0);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, coefficients);

        Samplers.DEPTH_RANGE.makeActive();
        GlStateManager.bindTexture(depthBounds);

        Samplers.NOISE.makeActive();
        if (NoiseTextures.BLUE_NOISE != null) {
            GlStateManager.bindTexture(NoiseTextures.BLUE_NOISE.getGlTextureId());
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        // MixinFramebuffer turns the main FBO's depthBuffer into a GL_TEXTURE_2D so HiZ
        // can sample it; attach it as a texture here so OIT shares the same depth state as the
        // main pass. Both mixin and this code ship in the same jar, so the swap is guaranteed.
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, renderTarget.depthBuffer, 0);
    }

    /**
     * Render out the min and max depth per fragment.
     */
    public void depthRange() {
        GlStateManager.depthMask(false);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        GL14.glBlendEquation(GL14.GL_MAX);

        // GL_MAX clear sentinel; must equal _flw_cullData.zfar so the cleared R/G are
        // <= every shader-written (-linearDepth, +linearDepth) and the real fragment wins.
        float far = FrameUniforms.getDepthFar();
        if (GlCompat.SUPPORTS_DSA) {
            GL45.glNamedFramebufferDrawBuffers(fbo, DEPTH_RANGE_DRAW_BUFFERS);
            GL45.glClearNamedFramebufferfv(fbo, GL11.GL_COLOR, 0, new float[]{-far, -far, 0, 0});
        } else {
            GL20.glDrawBuffers(DEPTH_RANGE_DRAW_BUFFERS);
            GlStateManager.clearColor(-far, -far, 0, 0);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    /**
     * Generate the coefficients to the transmittance function.
     */
    public void renderTransmittance() {
        GlStateManager.depthMask(false);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);

        if (GlCompat.SUPPORTS_DSA) {
            GL45.glNamedFramebufferDrawBuffers(fbo, RENDER_TRANSMITTANCE_DRAW_BUFFERS);

            GL45.glClearNamedFramebufferfv(fbo, GL11.GL_COLOR, 0, CLEAR_TO_ZERO);
            GL45.glClearNamedFramebufferfv(fbo, GL11.GL_COLOR, 1, CLEAR_TO_ZERO);
            GL45.glClearNamedFramebufferfv(fbo, GL11.GL_COLOR, 2, CLEAR_TO_ZERO);
            GL45.glClearNamedFramebufferfv(fbo, GL11.GL_COLOR, 3, CLEAR_TO_ZERO);
        } else {
            GL20.glDrawBuffers(RENDER_TRANSMITTANCE_DRAW_BUFFERS);
            GlStateManager.clearColor(0, 0, 0, 0);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    /**
     * If any fragment has its transmittance fall off to zero, search the transmittance
     * function to determine at what depth that occurs and write out to the depth buffer.
     */
    public void renderDepthFromTransmittance() {
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(false, false, false, false);
        GlStateManager.disableBlend();
        GlStateManager.depthFunc(GL11.GL_ALWAYS);

        if (GlCompat.SUPPORTS_DSA) {
            GL45.glNamedFramebufferDrawBuffers(fbo, DEPTH_ONLY_DRAW_BUFFERS);
        } else {
            GL20.glDrawBuffers(DEPTH_ONLY_DRAW_BUFFERS);
        }

        programs.getOitDepthProgram()
                .bind();

        drawFullscreenQuad();
    }

    /**
     * Sample the transmittance function and accumulate.
     */
    public void accumulate() {
        GlStateManager.depthMask(false);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);

        if (GlCompat.SUPPORTS_DSA) {
            GL45.glNamedFramebufferDrawBuffers(fbo, ACCUMULATE_DRAW_BUFFERS);

            GL45.glClearNamedFramebufferfv(fbo, GL11.GL_COLOR, 0, CLEAR_TO_ZERO);
        } else {
            GL20.glDrawBuffers(ACCUMULATE_DRAW_BUFFERS);
            GlStateManager.clearColor(0, 0, 0, 0);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    /**
     * Composite the accumulated luminance onto the main framebuffer.
     */
    public void composite() {
        Minecraft.getMinecraft()
                .getFramebuffer()
                .bindFramebuffer(false);

        // The composite shader writes out the closest depth to gl_FragDepth.
        // depthMask = true: OIT stuff renders on top of other transparent stuff.
        // depthMask = false: other transparent stuff renders on top of OIT stuff.
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableBlend();

        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GlStateManager.depthFunc(GL11.GL_ALWAYS);

        GlTextureUnit.T0.makeActive();
        GlStateManager.bindTexture(accumulate);

        programs.getOitCompositeProgram()
                .bind();

        drawFullscreenQuad();

        Minecraft.getMinecraft()
                .getFramebuffer()
                .bindFramebuffer(false);

        // Re-enable for vanilla MC's subsequent cutout draws (clouds, weather etc).
        GlStateManager.enableAlpha();
    }

    public void delete() {
        deleteTextures();
        GL30.glDeleteVertexArrays(vao);
    }

    private void drawFullscreenQuad() {
        GL30.glBindVertexArray(vao);

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private void deleteTextures() {
        if (depthBounds != -1) {
            GlStateManager.deleteTexture(depthBounds);
        }
        if (coefficients != -1) {
            GlStateManager.deleteTexture(coefficients);
        }
        if (accumulate != -1) {
            GlStateManager.deleteTexture(accumulate);
        }
        if (fbo != -1) {
            GL30.glDeleteFramebuffers(fbo);
        }
        FlwMemoryTracker._freeGpuMemory(trackedBytes);
        trackedBytes = 0;

        Samplers.COEFFICIENTS.makeActive();
        GlStateManager.bindTexture(0);
        Samplers.DEPTH_RANGE.makeActive();
        GlStateManager.bindTexture(0);
        // Restore ACTIVE_TEXTURE so vanilla bindTexture calls (e.g. GuiMainMenu.drawPanorama
        // after world unload) don't bind the panorama face PNGs to T7 and leave the blocks
        // atlas being sampled from T0.
        Samplers.DIFFUSE.makeActive();
    }

    private void maybeResizeFBO(int width, int height) {
        if (lastWidth == width && lastHeight == height) {
            return;
        }

        lastWidth = width;
        lastHeight = height;

        deleteTextures();
        trackedBytes = (long) width * height * BYTES_PER_PIXEL;
        FlwMemoryTracker._allocGpuMemory(trackedBytes);

        if (GlCompat.SUPPORTS_DSA) {
            fbo = GL45.glCreateFramebuffers();

            depthBounds = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            coefficients = GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
            accumulate = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);

            GL45.glTextureStorage2D(depthBounds, 1, GL30.GL_RG32F, width, height);
            GL45.glTextureStorage3D(coefficients, 1, GL30.GL_RGBA16F, width, height, 4);
            GL45.glTextureStorage2D(accumulate, 1, GL30.GL_RGBA16F, width, height);

            GL45.glNamedFramebufferTexture(fbo, GL30.GL_COLOR_ATTACHMENT0, depthBounds, 0);
            GL45.glNamedFramebufferTextureLayer(fbo, GL30.GL_COLOR_ATTACHMENT1, coefficients, 0, 0);
            GL45.glNamedFramebufferTextureLayer(fbo, GL30.GL_COLOR_ATTACHMENT2, coefficients, 0, 1);
            GL45.glNamedFramebufferTextureLayer(fbo, GL30.GL_COLOR_ATTACHMENT3, coefficients, 0, 2);
            GL45.glNamedFramebufferTextureLayer(fbo, GL30.GL_COLOR_ATTACHMENT4, coefficients, 0, 3);
            GL45.glNamedFramebufferTexture(fbo, GL30.GL_COLOR_ATTACHMENT5, accumulate, 0);
        } else {
            fbo = GL30.glGenFramebuffers();

            depthBounds = GL11.glGenTextures();
            coefficients = GL11.glGenTextures();
            accumulate = GL11.glGenTextures();

            GlTextureUnit.T0.makeActive();
            GlStateManager.bindTexture(0);

            GlStateManager.bindTexture(depthBounds);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, width, height, 0, GL11.GL_RGBA, GL11.GL_BYTE, 0);

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, coefficients);
            GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA16F, width, height, 4, 0, GL11.GL_RGBA,
                    GL11.GL_BYTE, 0);

            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GlStateManager.bindTexture(accumulate);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0, GL11.GL_RGBA, GL11.GL_BYTE, 0);

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, depthBounds, 0);
            GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, coefficients, 0, 0);
            GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT2, coefficients, 0, 1);
            GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT3, coefficients, 0, 2);
            GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT4, coefficients, 0, 3);
            GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT5, accumulate, 0);
        }
    }

}
