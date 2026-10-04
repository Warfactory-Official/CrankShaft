package dev.engine_room.flywheel.iris.compile.patches;

import com.google.common.primitives.Ints;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import dev.engine_room.flywheel.iris.engine.DeferredCompositePass;
import dev.engine_room.flywheel.iris.engine.DeferredReplayPlan;
import dev.engine_room.flywheel.iris.engine.DeferredReplayResources;
import dev.engine_room.flywheel.iris.engine.DeferredReplayShaders;
import net.irisshaders.iris.targets.RenderTargets;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class SundialReplayResources implements DeferredReplayResources {
    private static final int FIRST_TEXTURE = 10;
    private final RenderTargets targets;
    private final Set<Integer> readAlt;
    private final DeferredCompositePass last;
    private final int lastColorSlot;
    private final int[] saved = new int[7];
    private int width;
    private int height;
    private int depth;
    private int opaqueDepth;
    private int color;
    private int resolvedColor;
    private boolean copyColor;
    private boolean borrowedDepth;
    private long textureBytes;
    private int program;
    private int copyProgram;
    private int depthProgram;
    private int widthLocation;
    private int layerLocation;
    private int fbo;
    private int copyFbo;
    private int depthFbo;
    private int savedWeather;
    private int layerNodes;

    private SundialReplayResources(RenderTargets targets, DeferredCompositePass first, DeferredCompositePass last) {
        this.targets = targets;
        readAlt = first.flywheel$readAlt();
        this.last = last;
        lastColorSlot = Ints.indexOf(last.flywheel$drawBuffers(), 5);
        assert lastColorSlot >= 0;
    }

    static @Nullable DeferredReplayPlan plan(RenderTargets targets, List<?> passes) {
        var stages = new ArrayList<DeferredReplayPlan.Stage>();
        DeferredCompositePass after = null;
        for (Object entry : passes) {
            DeferredCompositePass pass = (DeferredCompositePass) entry;
            String name = pass.flywheel$name();
            if (!name.matches("composite[0-9]+")) continue;
            int index = Integer.parseInt(name.substring("composite".length()));
            if (index >= 1 && index <= 6) stages.add(new DeferredReplayPlan.Stage(pass, false));
            else if (index > 6 && after == null) after = pass;
        }
        if (stages.isEmpty()) return null;
        if (!stages.getFirst().pass().flywheel$name().equals("composite1")) {
            throw new IllegalStateException("Sundial replay entry changed");
        }
        return new DeferredReplayPlan(stages.toArray(DeferredReplayPlan.Stage[]::new), after,
                new DeferredReplayPlan.Storage(1, 2, 3, 4, 16, 1 << 16, 1 << 18, 16, 4), 5,
                new SundialReplayResources(targets, stages.getFirst().pass(), stages.getLast().pass()));
    }

    private static void sampler(int program, String name, int unit) {
        GL45C.glProgramUniform1i(program, GL20C.glGetUniformLocation(program, name), FIRST_TEXTURE + unit);
    }

    private static void bind(int unit, int texture) {
        GlStateManager._activeTexture(GL20C.GL_TEXTURE0 + FIRST_TEXTURE + unit);
        GlStateManager._bindTexture(texture);
        GL33C.glBindSampler(FIRST_TEXTURE + unit, 0);
    }

    private static void draw(int indexType, long offset) {
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, indexType, offset);
    }

    private static int depthBytes(int texture) {
        return switch (GL45C.glGetTextureLevelParameteri(texture, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT)) {
            case GL30C.GL_DEPTH32F_STENCIL8 -> 8;
            case GL14C.GL_DEPTH_COMPONENT16 -> 2;
            default -> 4;
        };
    }

    private static void complete(int fbo) {
        int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Sundial replay framebuffer: " + status);
    }

    @Override
    public int width() {
        return targets.getCurrentWidth();
    }

    @Override
    public int height() {
        return targets.getCurrentHeight();
    }

    @Override
    public boolean preservesViewport() {
        return true;
    }

    private void compile() {
        if (program != 0) return;
        program = DeferredReplayShaders.graphics("layer_materialize.frag");
        copyProgram = DeferredReplayShaders.graphics("layer_copy.frag");
        depthProgram = DeferredReplayShaders.graphics("layer_depth.frag");
        sampler(copyProgram, "color", 0);
        sampler(copyProgram, "weather", 1);
        sampler(depthProgram, "depth", 0);
        sampler(depthProgram, "color", 1);
        String[] samplers = {"saved0", "saved1", "saved2", "savedDepth", "saved5", "unused", "savedCloud", "backDepth"};
        for (int i = 0; i < samplers.length; i++) sampler(program, samplers[i], i);
        widthLocation = GL20C.glGetUniformLocation(program, "width");
        layerLocation = GL20C.glGetUniformLocation(program, "layerIndex");
    }

    private int color(int index) {
        return readAlt.contains(index) ? targets.get(index).getAltTexture() : targets.get(index).getMainTexture();
    }

    @Override
    public void snapshot() {
        compile();
        int w = width(), h = height();
        int currentDepth = ((GlTexture) targets.getDepthTexture()).glId();
        int currentOpaque = ((GlTexture) targets.getDepthTextureNoTranslucents()).glId();
        boolean resized = w != width || h != height || currentDepth != depth || currentOpaque != opaqueDepth;
        if (resized) {
            release();
            width = w;
            height = h;
            depth = currentDepth;
            opaqueDepth = currentOpaque;
            textureBytes = (long) w * h * (44 + depthBytes(depth) + depthBytes(opaqueDepth));
            fbo = GL45C.glCreateFramebuffers();
            copyFbo = GL45C.glCreateFramebuffers();
            depthFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferReadBuffer(depthFbo, GL11C.GL_NONE);
            savedWeather = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(savedWeather, 1, targets.get(4).getInternalFormat().getGlFormat(), w, h);
            layerNodes = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(layerNodes, 1, GL30C.GL_R32UI, w, h);
            textureBytes += (long) w * h * 4;
            GL45C.glNamedFramebufferTexture(fbo, GL30C.GL_COLOR_ATTACHMENT4, layerNodes, 0);
            for (int i = 0; i < saved.length; i++) {
                saved[i] = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
                int format = i == 3 || i == 5 ? GL45C.glGetTextureLevelParameteri(i == 3 ? depth : opaqueDepth,
                        0, GL11C.GL_TEXTURE_INTERNAL_FORMAT) : targets.get(i < 3 ? i : 5).getInternalFormat()
                                                                      .getGlFormat();
                GL45C.glTextureStorage2D(saved[i], 1, format, w, h);
                GL45C.glTextureParameteri(saved[i], GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL45C.glTextureParameteri(saved[i], GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            }
            GL45C.glNamedFramebufferDrawBuffers(fbo, new int[]{GL30C.GL_COLOR_ATTACHMENT0,
                    GL30C.GL_COLOR_ATTACHMENT1, GL30C.GL_COLOR_ATTACHMENT2, GL30C.GL_COLOR_ATTACHMENT3, GL30C.GL_COLOR_ATTACHMENT4});
            GL45C.glNamedFramebufferDrawBuffers(copyFbo,
                    new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        }
        for (int i = 0; i < 4; i++) {
            int source = i == 3 ? depth : color(i);
            copy(source, saved[i]);
            GL45C.glNamedFramebufferTexture(fbo, i == 3 ? GL30C.GL_DEPTH_ATTACHMENT : GL30C.GL_COLOR_ATTACHMENT0 + i,
                    source, 0);
        }
        color = color(5);
        GL45C.glNamedFramebufferTexture(fbo, GL30C.GL_COLOR_ATTACHMENT3, color, 0);
        copy(color, saved[6]);
        copy(color(4), savedWeather);
        copy(opaqueDepth, saved[5]);
        GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT0, saved[4], 0);
        GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT1, color(4), 0);
        GL45C.glNamedFramebufferTexture(depthFbo, GL30C.GL_DEPTH_ATTACHMENT, opaqueDepth, 0);
        resolvedColor = last.flywheel$framebuffer().getColorAttachment(lastColorSlot);
        copyColor = resolvedColor != color;
        GL45C.glNamedFramebufferTexture(depthFbo, GL30C.GL_COLOR_ATTACHMENT0, copyColor ? color : 0, 0);
        GL45C.glNamedFramebufferDrawBuffer(depthFbo, copyColor ? GL30C.GL_COLOR_ATTACHMENT0 : GL11C.GL_NONE);
        if (resized) {
            complete(fbo);
            complete(copyFbo);
            complete(depthFbo);
        }
        borrowedDepth = true;
    }

    @Override
    public void materialize(int layer, int indexType, long offset) {
        bind(0, color);
        bind(1, savedWeather);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, copyFbo);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._colorMask(15);
        for (int i = 0; i < 4; i++) GlStateManager._disableBlend(i);
        GlStateManager._glUseProgram(copyProgram);
        draw(indexType, offset);
        for (int i = 0; i < 8; i++) bind(i, i == 7 ? opaqueDepth : saved[i]);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_ALWAYS);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(15);
        for (int i = 0; i < 4; i++) GlStateManager._disableBlend(i);
        GlStateManager._glUseProgram(program);
        GL45C.glProgramUniform1i(program, widthLocation, width);
        GL45C.glProgramUniform1i(program, layerLocation, layer);
        draw(indexType, offset);
        GL43C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_FRAMEBUFFER_BARRIER_BIT);
    }

    @Override
    public void advance(int layer, int indexType, long offset) {
        bind(0, depth);
        bind(1, copyColor ? resolvedColor : saved[4]);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, depthFbo);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_ALWAYS);
        GlStateManager._depthMask(true);
        GlStateManager._glUseProgram(depthProgram);
        draw(indexType, offset);
    }

    @Override
    public void bindGraphicsResources(DeferredCompositePass pass, int layer) {
        GL42C.glBindImageTexture(7, layerNodes, 0, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32UI);
    }

    @Override
    public void restore() {
        if (borrowedDepth) copy(saved[5], opaqueDepth);
        borrowedDepth = false;
    }

    private void copy(int from, int to) {
        GL43C.glCopyImageSubData(from, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                to, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    @Override
    public long allocatedBytes() {
        return textureBytes;
    }

    @Override
    public void release() {
        for (int texture : saved) if (texture != 0) GlStateManager._deleteTexture(texture);
        if (fbo != 0) GlStateManager._glDeleteFramebuffers(fbo);
        if (copyFbo != 0) GlStateManager._glDeleteFramebuffers(copyFbo);
        if (depthFbo != 0) GlStateManager._glDeleteFramebuffers(depthFbo);
        if (savedWeather != 0) GlStateManager._deleteTexture(savedWeather);
        if (layerNodes != 0) GlStateManager._deleteTexture(layerNodes);
        Arrays.fill(saved, 0);
        fbo = copyFbo = depthFbo = savedWeather = layerNodes = 0;
        width = height = 0;
        textureBytes = 0;
    }

    @Override
    public void close() {
        restore();
        release();
        if (program != 0) GlStateManager.glDeleteProgram(program);
        if (copyProgram != 0) GlStateManager.glDeleteProgram(copyProgram);
        if (depthProgram != 0) GlStateManager.glDeleteProgram(depthProgram);
        program = copyProgram = depthProgram = 0;
    }
}
