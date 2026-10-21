package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.google.common.primitives.Ints;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.*;

import javax.annotation.Nullable;
import java.util.*;

/**
 * SEUS material/lighting share a texture; replay preserves both quadrants.
 */
final class SeusReplayResources implements DeferredReplayResources {
    private static final int FIRST_TEXTURE = 10;
    private final DeferredReplayTargets targets;
    private final Set<Integer> readAlt;
    private final DeferredReplayPass last;
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

    private SeusReplayResources(DeferredReplayTargets targets, DeferredReplayPass first, DeferredReplayPass last,
                                boolean motion, boolean restoreColor0) {
        this.targets = targets;
        readAlt = first.readAlt();
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
        lastColorSlot = Ints.indexOf(last.drawBuffers(), 1);
        if (lastColorSlot < 0) throw new IllegalStateException("SEUS material exit does not write color");
    }

    static @Nullable DeferredReplayPlan plan(DeferredReplayTargets targets, List<?> passes, boolean motion) {
        var stages = new ArrayList<DeferredReplayPlan.Stage>();
        DeferredReplayPass after = null;
        for (Object entry : passes) {
            DeferredReplayPass pass = (DeferredReplayPass) entry;
            String name = pass.name();
            if (name.equals("composite")) stages.add(new DeferredReplayPlan.Stage(pass, false));
            else if (name.matches("composite[1-4]")) stages.add(new DeferredReplayPlan.Stage(pass, false));
            else if (!stages.isEmpty() && after == null) after = pass;
        }
        if (stages.isEmpty()) return null;
        if (stages.size() != 5 || !stages.getFirst().pass().name()
                                         .equals("composite"))
            throw new IllegalStateException("SEUS material replay stages changed");
        boolean restoreColor0 = stages.stream().anyMatch(
                stage -> Ints.contains(stage.pass().drawBuffers(), 0)
                        || Arrays.stream(stage.pass().computes())
                                 .anyMatch(Objects::nonNull));
        return new DeferredReplayPlan(stages.toArray(DeferredReplayPlan.Stage[]::new), after,
                new DeferredReplayPlan.Storage(1, 2, 3, 4, 16, 1 << 16, 1 << 18, 16, 4), 0,
                new SeusReplayResources(targets, stages.getFirst().pass(), stages.getLast().pass(), motion,
                        restoreColor0));
    }

    private static void sampler(int program, String name, int unit) {
        GL41C.glProgramUniform1i(program, GL20C.glGetUniformLocation(program, name), FIRST_TEXTURE + unit);
    }

    private static void bind(int unit, int texture) {
        GlStateTracker.hostActiveTexture(GL13C.GL_TEXTURE0 + FIRST_TEXTURE + unit);
        GlStateManager.bindTexture(texture);
        GL33C.glBindSampler(FIRST_TEXTURE + unit, 0);
    }

    private static void complete(int fbo) {
        int status = GL45C.glCheckNamedFramebufferStatus(fbo, GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("SEUS replay framebuffer: " + status);
    }

    @Override
    public int width() {
        return targets.width();
    }

    @Override
    public int height() {
        return targets.height();
    }

    @Override
    public boolean preservesViewport() {
        return true;
    }

    private void compileSort() {
        if (sortProgram != 0) return;
        sortProgram = DeferredReplayShaders.compute("seus_sort.comp", SeusReplayShaders.sort()).handle();
        sampler(sortProgram, "opaqueDepth", 0);
        sortSizeLocation = GL20C.glGetUniformLocation(sortProgram, "screenSize");
        GL41C.glProgramUniform1i(sortProgram, GL20C.glGetUniformLocation(sortProgram, "flw_oitActive"), 1);
    }

    private void compileReplay() {
        if (materialProgram != 0) return;
        materialProgram = DeferredReplayShaders.graphics("seus_materialize.frag", compilation -> {
            if (motion) compilation.define("FLW_SEUS_MOTION");
        }).handle();
        depthProgram = DeferredReplayShaders.graphics("layer_depth.frag").handle();
        restoreProgram = DeferredReplayShaders.graphics("seus_restore.frag", compilation -> {
            if (restoreColor0) compilation.define("FLW_SEUS_RESTORE_COLOR0");
        }).handle();
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
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        // Textured effects are captured before the host copies this frame's opaque depth.
        bind(0, targets.opaqueDepth());
        GlProgram.bind(sortProgram);
        int w = width(), h = height();
        if (sortWidth != w || sortHeight != h) {
            GL41C.glProgramUniform2f(sortProgram, sortSizeLocation, w, h);
            sortWidth = w;
            sortHeight = h;
        }
        // HalfScreen includes the separator texel and any rasterization at the crop edge.
        GL43C.glDispatchCompute((w / 2 + 8) >> 3, (h / 2 + 8) >> 3, 1);
    }

    private int color(int index) {
        return readAlt.contains(index) ? targets.get(index).alt() : targets.get(index).main();
    }

    private int bank(int index, int alternate) {
        return alternate == 0 ? targets.get(index).main() : targets.get(index).alt();
    }

    @Override
    public void snapshot() {
        compileReplay();
        int w = width(), h = height();
        int currentDepth = targets.depth();
        int currentOpaque = targets.opaqueDepth();
        var changedInputs = refreshInputs();
        var resized = width != w || height != h || depth != currentDepth || opaqueDepth != currentOpaque;
        if (resized) {
            release();
            width = w;
            height = h;
            GL41C.glProgramUniform1i(materialProgram, widthLocation, width);
            depth = currentDepth;
            opaqueDepth = currentOpaque;
            restoreFbo = GL45C.glCreateFramebuffers();
            materialFbo = GL45C.glCreateFramebuffers();
            advanceFbo = GL45C.glCreateFramebuffers();
            for (int i = 0; i < snapshotIndices.length; i++) {
                int format = targets.get(snapshotIndices[i]).format();
                saved[i] = texture(format, inputs[i]);
            }
            savedDepth = texture(GuestOitTargets.sizedDepthFormat(depth), depth);
            savedOpaque = texture(GuestOitTargets.sizedDepthFormat(opaqueDepth), opaqueDepth);
            backColor = texture(targets.get(1).format(), color(1));
            layerNodes = texture(GL30C.GL_R32UI, 0);
        }
        if (resized || changedInputs) attachTargets();
        for (int i = 0; i < inputs.length; i++) copy(inputs[i], saved[i]);
        copy(depth, savedDepth);
        copy(opaqueDepth, savedOpaque);
        copy(color(1), backColor);
        // The checked material stages have no authored image bindings to replace this readonly slot.
        GL42C.glBindImageTexture(7, layerNodes, 0, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32UI);
        borrowedDepth = true;
    }

    private boolean input(int slot, int texture) {
        var changed = inputs[slot] != texture;
        inputs[slot] = texture;
        return changed;
    }

    private boolean refreshInputs() {
        var changed = false;
        if (restoreColor0) {
            changed |= input(0, bank(0, 0));
            changed |= input(1, bank(0, 1));
        }
        changed |= input(materialSlot, color(1));
        changed |= input(attributesSlot, color(2));
        changed |= input(reflectionSlot, bank(7, 0));
        changed |= input(reflectionSlot + 1, bank(7, 1));
        if (motion) changed |= input(motionSlot, color(8));
        return changed;
    }

    private void attachTargets() {
        // Legacy hosts can change the read bank between frames without resizing the targets.
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
                readAlt.contains(1) ? targets.get(1).main() : targets.get(1).alt(), 0);
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

    @Override
    public void materialize(int layer, int type, long offset) {
        // Framebuffer restoration uses normal GL ordering for the preceding reads and following material passes.
        if (restoreColor0) {
            bind(0, saved[0]);
            bind(1, saved[1]);
        }
        bind(2, saved[reflectionSlot]);
        bind(3, saved[reflectionSlot + 1]);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, restoreFbo);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.disableBlend();
        for (int i = 0; i < 5; i++) GL30C.glDisablei(GL11C.GL_BLEND, i);
        GlProgram.bind(restoreProgram);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
        bind(0, saved[materialSlot]);
        bind(1, saved[attributesSlot]);
        bind(2, savedDepth);
        bind(3, backColor);
        bind(4, opaqueDepth);
        if (motion) bind(5, saved[motionSlot]);
        bind(6, savedOpaque);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, materialFbo);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11C.GL_ALWAYS);
        GlStateManager.depthMask(true);
        GlProgram.bind(materialProgram);
        GL41C.glProgramUniform1i(materialProgram, layerLocation, layer);
        GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, type, offset);
    }

    @Override
    public void advance(int layer, int type, long offset) {
        bind(0, depth);
        bind(1, last.outputTexture(lastColorSlot));
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, advanceFbo);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11C.GL_ALWAYS);
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
        ContractProperties.Blend.OFF.apply(0);
        GlProgram.bind(depthProgram);
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

    @Override
    public long allocatedBytes() {
        return textureBytes;
    }

    @Override
    public void release() {
        for (int texture : saved) if (texture != 0) GlStateManager.deleteTexture(texture);
        for (int texture : new int[]{savedDepth, savedOpaque, backColor, layerNodes})
            if (texture != 0) GlStateManager.deleteTexture(texture);
        if (materialFbo != 0) GL30C.glDeleteFramebuffers(materialFbo);
        if (restoreFbo != 0) GL30C.glDeleteFramebuffers(restoreFbo);
        if (advanceFbo != 0) GL30C.glDeleteFramebuffers(advanceFbo);
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
            if (program != 0) GL20C.glDeleteProgram(program);
        sortProgram = materialProgram = depthProgram = restoreProgram = 0;
        sortWidth = sortHeight = 0;
    }
}
