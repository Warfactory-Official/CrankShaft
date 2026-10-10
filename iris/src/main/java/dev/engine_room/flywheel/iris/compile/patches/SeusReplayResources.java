package dev.engine_room.flywheel.iris.compile.patches;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
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
import java.util.Objects;

/** SEUS material and lighting banks share a texture; replay preserves both quadrants independently. */
final class SeusReplayResources implements DeferredReplayResources {
    private static final int FIRST_TEXTURE = 10;
    private final RenderTargets targets;
    private final ImmutableSet<Integer> readAlt;
    private final DeferredCompositePass last;
    private final int[] snapshotIndices;
    private final int[] inputs;
    private final boolean motion;
    private final boolean restoreColor0;
    private final int lastColorSlot;
    private final int materialSlot, attributesSlot, reflectionSlot, motionSlot;
    private final int[] saved;
    private int width, height, depth, opaqueDepth;
    private int savedDepth, savedOpaque, backColor, layerNodes;
    private int materialProgram, depthProgram, sortProgram, restoreProgram;
    private int restoreFbo, materialFbo, advanceFbo;
    private int widthLocation, layerLocation, sortSizeLocation;
    private int sortWidth, sortHeight;
    private boolean borrowedDepth;
    private long textureBytes;

    private SeusReplayResources(RenderTargets targets, DeferredCompositePass first, DeferredCompositePass last,
                                boolean motion, boolean restoreColor0) {
        this.targets = targets;
        readAlt = first.flywheel$readAlt();
        this.last = last;
        this.motion = motion;
        this.restoreColor0 = restoreColor0;
        materialSlot = restoreColor0 ? 2 : 0;
        attributesSlot = materialSlot + 1;
        reflectionSlot = materialSlot + 2;
        motionSlot = reflectionSlot + 2;
        snapshotIndices = new int[motionSlot + (motion ? 1 : 0)];
        snapshotIndices[materialSlot] = 1;
        snapshotIndices[attributesSlot] = 2;
        snapshotIndices[reflectionSlot] = snapshotIndices[reflectionSlot + 1] = 7;
        if (motion) snapshotIndices[motionSlot] = 8;
        inputs = new int[snapshotIndices.length];
        saved = new int[snapshotIndices.length];
        lastColorSlot = Ints.indexOf(last.flywheel$drawBuffers(), 1);
        if (lastColorSlot < 0) throw new IllegalStateException("SEUS material exit does not write color");
    }

    static @Nullable DeferredReplayPlan plan(RenderTargets targets, List<?> passes, boolean motion) {
        var stages = new ArrayList<DeferredReplayPlan.Stage>();
        DeferredCompositePass after = null;
        for (Object entry : passes) {
            DeferredCompositePass pass = (DeferredCompositePass) entry;
            String name = pass.flywheel$name();
            if (name.equals("composite")) stages.add(new DeferredReplayPlan.Stage(pass, false));
            else if (name.matches("composite[1-4]")) stages.add(new DeferredReplayPlan.Stage(pass, false));
            else if (!stages.isEmpty() && after == null) after = pass;
        }
        if (stages.isEmpty()) return null;
        if (stages.size() != 5 || !stages.getFirst().pass().flywheel$name().equals("composite"))
            throw new IllegalStateException("SEUS material replay stages changed");
        boolean restoreColor0 = stages.stream().anyMatch(stage -> Ints.contains(stage.pass().flywheel$drawBuffers(), 0)
                || Arrays.stream(stage.pass().flywheel$computes()).anyMatch(Objects::nonNull));
        return new DeferredReplayPlan(stages.toArray(DeferredReplayPlan.Stage[]::new), after,
                new DeferredReplayPlan.Storage(1, 2, 3, 4, 16, 1 << 16, 1 << 18, 16, 4), 0,
                new SeusReplayResources(targets, stages.getFirst().pass(), stages.getLast().pass(), motion, restoreColor0));
    }

    @Override
    public int width() { return targets.getCurrentWidth(); }

    @Override
    public int height() { return targets.getCurrentHeight(); }

    @Override
    public boolean preservesViewport() { return true; }

    private void compileSort() {
        if (sortProgram != 0) return;
        String sort = DeferredOitProfile.resource("layer_sort.glsl")
                .replace("flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);", "")
                .replace("_FLW_VISIBILITY_BODY", "if (uintBitsToFloat(flw_nodes[flw_node].y) < flw_opaqueDepth) ++flw_visible;")
                .replace("_FLW_SORT_PIXEL", "pixel")
                .replace("_FLW_SORT_COUNTS_LAYERS", "true")
                .replace("_FLW_SORT_LAYER_BIAS", "0u")
                .replace("flw_length + 0u", "flw_visible")
                .replace(" * 2u", "");
        sortProgram = DeferredReplayShaders.compute("seus_sort.comp", compilation -> {
            // HRR 3 uses the final word for motion, so every bit participates in an equal-depth tie.
            String storage = DeferredOitProfile.resource("layer_storage.glsl")
                    .replace("return (a.w & FLW_LAYER_UNLIT) <= (b.w & FLW_LAYER_UNLIT);", "return a.w <= b.w;");
            compilation.appendComponent(new ShaderAssembly.RawSource("seus_sort_storage",
                    "uniform vec2 screenSize;\nuniform sampler2D opaqueDepth;\n" + storage));
            compilation.appendComponent(new ShaderAssembly.RawSource("seus_sort_lists",
                    "void flw_sortSeus(uvec2 pixel) {\nuint flw_visible = 0u;\n"
                            + "float flw_opaqueDepth = 1.0 - texelFetch(opaqueDepth, ivec2(pixel), 0).r;\n"
                            + sort + "\n}"));
        });
        sampler(sortProgram, "opaqueDepth", 0);
        sortSizeLocation = GL20C.glGetUniformLocation(sortProgram, "screenSize");
        GL45C.glProgramUniform1i(sortProgram, GL20C.glGetUniformLocation(sortProgram, "flw_oitActive"), 1);
    }

    private void compileReplay() {
        if (materialProgram != 0) return;
        materialProgram = DeferredReplayShaders.graphics("seus_materialize.frag", compilation -> {
            if (motion) compilation.define("FLW_SEUS_MOTION");
        });
        depthProgram = DeferredReplayShaders.graphics("layer_depth.frag");
        restoreProgram = DeferredReplayShaders.graphics("seus_restore.frag", compilation -> {
            if (restoreColor0) compilation.define("FLW_SEUS_RESTORE_COLOR0");
        });
        sampler(restoreProgram, "saved0[0]", 0);
        sampler(restoreProgram, "saved0[1]", 1);
        sampler(restoreProgram, "saved7[0]", 2);
        sampler(restoreProgram, "saved7[1]", 3);
        String[] materialSamplers = {"saved1", "saved2", "savedDepth", "backColor", "backDepth", "savedMotion", "savedOpaque"};
        for (int i = 0; i < materialSamplers.length; i++) sampler(materialProgram, materialSamplers[i], i);
        sampler(depthProgram, "depth", 0);
        sampler(depthProgram, "color", 1);
        widthLocation = GL20C.glGetUniformLocation(materialProgram, "width");
        layerLocation = GL20C.glGetUniformLocation(materialProgram, "layerIndex");
    }

    @Override
    public void prepareCapture() {
        compileSort();
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        // Native textured effects are captured before Iris copies this frame's opaque depth.
        bind(0, ((GlTexture) targets.getDepthTextureNoTranslucents()).glId());
        GlStateManager._glUseProgram(sortProgram);
        int w = width(), h = height();
        if (sortWidth != w || sortHeight != h) {
            GL45C.glProgramUniform2f(sortProgram, sortSizeLocation, w, h);
            sortWidth = w;
            sortHeight = h;
        }
        // HalfScreen includes the separator texel and any rasterization at the crop edge.
        GL43C.glDispatchCompute((w / 2 + 8) >> 3, (h / 2 + 8) >> 3, 1);
    }

    private int color(int index) {
        return readAlt.contains(index) ? targets.get(index).getAltTexture() : targets.get(index).getMainTexture();
    }

    private int bank(int index, int alternate) {
        return alternate == 0 ? targets.get(index).getMainTexture() : targets.get(index).getAltTexture();
    }

    @Override
    public void snapshot() {
        compileReplay();
        int w = width(), h = height();
        int currentDepth = ((GlTexture) targets.getDepthTexture()).glId();
        int currentOpaque = ((GlTexture) targets.getDepthTextureNoTranslucents()).glId();
        if (width != w || height != h || depth != currentDepth || opaqueDepth != currentOpaque) {
            release();
            width = w;
            height = h;
            GL45C.glProgramUniform1i(materialProgram, widthLocation, width);
            depth = currentDepth;
            opaqueDepth = currentOpaque;
            restoreFbo = GL45C.glCreateFramebuffers();
            materialFbo = GL45C.glCreateFramebuffers();
            advanceFbo = GL45C.glCreateFramebuffers();
            if (restoreColor0) {
                inputs[0] = bank(0, 0);
                inputs[1] = bank(0, 1);
            }
            inputs[materialSlot] = color(1);
            inputs[attributesSlot] = color(2);
            inputs[reflectionSlot] = bank(7, 0);
            inputs[reflectionSlot + 1] = bank(7, 1);
            if (motion) inputs[motionSlot] = color(8);
            for (int i = 0; i < snapshotIndices.length; i++) {
                int format = targets.get(snapshotIndices[i]).getInternalFormat().getGlFormat();
                saved[i] = texture(format, inputs[i]);
            }
            savedDepth = texture(GL45C.glGetTextureLevelParameteri(depth, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT), depth);
            savedOpaque = texture(GL45C.glGetTextureLevelParameteri(opaqueDepth, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT), opaqueDepth);
            backColor = texture(targets.get(1).getInternalFormat().getGlFormat(), color(1));
            layerNodes = texture(GL30C.GL_R32UI, 0);
            int[] restoreInputs = restoreColor0 ? new int[]{0, 1, reflectionSlot, reflectionSlot + 1}
                    : new int[]{reflectionSlot, reflectionSlot + 1};
            int[] restoreBuffers = new int[restoreInputs.length];
            for (int i = 0; i < restoreInputs.length; i++) {
                GL45C.glNamedFramebufferTexture(restoreFbo, GL30C.GL_COLOR_ATTACHMENT0 + i,
                        inputs[restoreInputs[i]], 0);
                restoreBuffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
            }
            GL45C.glNamedFramebufferDrawBuffers(restoreFbo, restoreBuffers);
            GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_COLOR_ATTACHMENT0, color(1), 0);
            GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_COLOR_ATTACHMENT1, color(2), 0);
            if (motion) GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_COLOR_ATTACHMENT2, color(8), 0);
            GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_COLOR_ATTACHMENT3, layerNodes, 0);
            GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_COLOR_ATTACHMENT4,
                    readAlt.contains(1) ? targets.get(1).getMainTexture() : targets.get(1).getAltTexture(), 0);
            GL45C.glNamedFramebufferTexture(materialFbo, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
            GL45C.glNamedFramebufferDrawBuffers(materialFbo, new int[]{GL30C.GL_COLOR_ATTACHMENT0,
                    GL30C.GL_COLOR_ATTACHMENT1, motion ? GL30C.GL_COLOR_ATTACHMENT2 : GL11C.GL_NONE,
                    GL30C.GL_COLOR_ATTACHMENT3, GL30C.GL_COLOR_ATTACHMENT4});
            GL45C.glNamedFramebufferTexture(advanceFbo, GL30C.GL_DEPTH_ATTACHMENT, opaqueDepth, 0);
            GL45C.glNamedFramebufferTexture(advanceFbo, GL30C.GL_COLOR_ATTACHMENT0, backColor, 0);
            GL45C.glNamedFramebufferDrawBuffer(advanceFbo, GL30C.GL_COLOR_ATTACHMENT0);
            complete(restoreFbo);
            complete(materialFbo);
            complete(advanceFbo);
        }
        for (int i = 0; i < inputs.length; i++) copy(inputs[i], saved[i]);
        copy(depth, savedDepth);
        copy(opaqueDepth, savedOpaque);
        copy(color(1), backColor);
        // The checked material stages have no authored image bindings to replace this readonly slot.
        GL42C.glBindImageTexture(7, layerNodes, 0, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32UI);
        borrowedDepth = true;
    }

    @Override
    public void materialize(int layer, int type, long offset) {
        // Framebuffer restoration uses normal GL ordering for the preceding reads and following material passes.
        if (restoreColor0) {
            bind(0, saved[0]);
            bind(1, saved[1]);
        }
        bind(2, saved[reflectionSlot]);
        bind(3, saved[reflectionSlot + 1]);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, restoreFbo);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._colorMask(15);
        for (int i = 0; i < 5; i++) GlStateManager._disableBlend(i);
        GlStateManager._glUseProgram(restoreProgram);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
        bind(0, saved[materialSlot]);
        bind(1, saved[attributesSlot]);
        bind(2, savedDepth);
        bind(3, backColor);
        bind(4, opaqueDepth);
        if (motion) bind(5, saved[motionSlot]);
        bind(6, savedOpaque);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, materialFbo);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_ALWAYS);
        GlStateManager._depthMask(true);
        GlStateManager._glUseProgram(materialProgram);
        GL45C.glProgramUniform1i(materialProgram, layerLocation, layer);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
    }

    @Override
    public void advance(int layer, int type, long offset) {
        bind(0, depth);
        bind(1, last.flywheel$framebuffer().getColorAttachment(lastColorSlot));
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, advanceFbo);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_ALWAYS);
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(15);
        GlStateManager._disableBlend(0);
        GlStateManager._glUseProgram(depthProgram);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
    }

    @Override
    public void restore() {
        if (borrowedDepth) copy(savedOpaque, opaqueDepth);
        borrowedDepth = false;
    }

    private int texture(int format, int source) {
        int texture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, format, width, height);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        int bytes = source == 0 ? 4 : GL45C.glGetTextureLevelParameteri(source, 0, GL11C.GL_TEXTURE_RED_SIZE)
                + GL45C.glGetTextureLevelParameteri(source, 0, GL11C.GL_TEXTURE_GREEN_SIZE)
                + GL45C.glGetTextureLevelParameteri(source, 0, GL11C.GL_TEXTURE_BLUE_SIZE)
                + GL45C.glGetTextureLevelParameteri(source, 0, GL11C.GL_TEXTURE_ALPHA_SIZE)
                + GL45C.glGetTextureLevelParameteri(source, 0, GL14C.GL_TEXTURE_DEPTH_SIZE)
                + GL45C.glGetTextureLevelParameteri(source, 0, GL30C.GL_TEXTURE_STENCIL_SIZE);
        int storageBytes = format == GL30C.GL_DEPTH32F_STENCIL8 ? 8 : source == 0 ? bytes : (bytes + 7) / 8;
        textureBytes += (long) width * height * storageBytes;
        return texture;
    }

    private void copy(int from, int to) {
        GL43C.glCopyImageSubData(from, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                to, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    private static void sampler(int program, String name, int unit) {
        GL45C.glProgramUniform1i(program, GL20C.glGetUniformLocation(program, name), FIRST_TEXTURE + unit);
    }

    private static void bind(int unit, int texture) {
        GlStateManager._activeTexture(GL20C.GL_TEXTURE0 + FIRST_TEXTURE + unit);
        GlStateManager._bindTexture(texture);
        GL33C.glBindSampler(FIRST_TEXTURE + unit, 0);
    }

    private static void complete(int fbo) {
        int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("SEUS replay framebuffer: " + status);
    }

    @Override
    public long allocatedBytes() { return textureBytes; }

    @Override
    public void release() {
        for (int texture : saved) if (texture != 0) GlStateManager._deleteTexture(texture);
        for (int texture : new int[]{savedDepth, savedOpaque, backColor, layerNodes})
            if (texture != 0) GlStateManager._deleteTexture(texture);
        if (materialFbo != 0) GlStateManager._glDeleteFramebuffers(materialFbo);
        if (restoreFbo != 0) GlStateManager._glDeleteFramebuffers(restoreFbo);
        if (advanceFbo != 0) GlStateManager._glDeleteFramebuffers(advanceFbo);
        Arrays.fill(saved, 0);
        savedDepth = savedOpaque = backColor = layerNodes = restoreFbo = materialFbo = advanceFbo = 0;
        width = height = 0;
        textureBytes = 0;
    }

    @Override
    public void close() {
        restore();
        release();
        for (int program : new int[]{sortProgram, materialProgram, depthProgram, restoreProgram})
            if (program != 0) GlStateManager.glDeleteProgram(program);
        sortProgram = materialProgram = depthProgram = restoreProgram = 0;
        sortWidth = sortHeight = 0;
    }
}
