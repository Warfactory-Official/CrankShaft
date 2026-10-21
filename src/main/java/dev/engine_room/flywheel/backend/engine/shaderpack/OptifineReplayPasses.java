package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import net.minecraft.client.renderer.GlStateManager;
import net.optifine.shaders.Program;
import net.optifine.shaders.Shaders;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.util.*;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

final class OptifineReplayPasses {
    private static final DeferredReplayPass.Compute[] NO_COMPUTES = new DeferredReplayPass.Compute[0];
    private final OptifineReplayTargets targets;
    private final List<DeferredReplayPass> passes;
    private final Map<Program, Pass> byProgram = new IdentityHashMap<>();
    private final IntSet frameAlt = new IntOpenHashSet();
    private final IntSet frameFlipped = new IntOpenHashSet();
    private final int[] initialTextures = new int[16];

    OptifineReplayPasses(Program[] programs, OptifineReplayTargets targets) {
        this.targets = targets;
        var passes = new ArrayList<DeferredReplayPass>();
        for (var program : programs) {
            if (program.getId() == 0) continue;
            var pass = new Pass(program);
            byProgram.put(program, pass);
            passes.add(pass);
        }
        this.passes = passes;
    }

    private static void bind(int unit, int texture) {
        GlStateTracker.hostActiveTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager.bindTexture(texture);
    }

    List<DeferredReplayPass> passes() {
        return passes;
    }

    DeferredReplayPass find(Program program) {
        return byProgram.get(program);
    }

    void prepareFrame() {
        // OptiFine binds family custom textures once, then each output flip replaces that unit with its target.
        for (var unit = 0; unit < initialTextures.length; unit++)
            initialTextures[unit] = GlStateManager.textureState[unit].textureName;
        var flipped = frameFlipped;
        flipped.clear();
        var alt = frameAlt;
        targets.readAlt(alt);
        for (var entry : passes) {
            var pass = (Pass) entry;
            pass.readAlt.clear();
            pass.readAlt.addAll(alt);
            pass.flippedBefore.clear();
            pass.flippedBefore.addAll(flipped);
            for (var index = 0; index < pass.toggles.length; index++)
                if (pass.toggles[index]) {
                    flipped.add(index);
                    if (!alt.remove(index)) alt.add(index);
                }
        }
    }

    void prepareNative(DeferredReplayPass pass) {
        ((Pass) pass).prepare();
    }

    private final class Pass implements DeferredReplayPass {
        private final Program program;
        private final int[] outputs;
        private final boolean[] toggles;
        private final IntSet readAlt = new IntOpenHashSet();
        private final IntSet flippedBefore = new IntOpenHashSet();

        Pass(Program program) {
            this.program = program;
            var buffers = program.getDrawBuffers();
            outputs = new int[buffers.remaining()];
            for (var index = 0; index < outputs.length; index++) {
                var attachment = buffers.get(buffers.position() + index);
                outputs[index] = attachment == GL11.GL_NONE ? -1 : attachment - GL30.GL_COLOR_ATTACHMENT0;
            }
            toggles = program.getToggleColorTextures();
            var scale = program.getRenderScale();
            if (program.getCountInstances() > 1 || scale != null
                    && (scale.getScale() != 1 || scale.getOffsetX() != 0 || scale.getOffsetY() != 0))
                throw new UnsupportedOperationException("Unsupported native composite shape: " + program.getName());
        }

        @Override
        public String name() {
            return program.getName();
        }

        @Override
        public int program() {
            return program.getId();
        }

        @Override
        public Compute[] computes() {
            return NO_COMPUTES;
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
        public int[] drawBuffers() {
            return outputs;
        }

        @Override
        public IntSet readAlt() {
            return readAlt;
        }

        @Override
        public int outputTexture(int slot) {
            var index = outputs[slot];
            return readAlt.contains(index) ? targets.get(index).main() : targets.get(index).alt();
        }

        void prepare() {
            targets.applyReadAlt(readAlt);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, Shaders.dfb);
            // TODO (ARB_multi_bind): Restore native textures in one call while updating Minecraft's texture cache.
            for (var unit = 0; unit < initialTextures.length; unit++) bind(unit, initialTextures[unit]);
            var units = Shaders.colorTextureImageUnit;
            for (var index = 0; index < toggles.length; index++) {
                var target = targets.get(index);
                if (flippedBefore.contains(index))
                    bind(units[index], readAlt.contains(index) ? target.alt() : target.main());
                GL45.glNamedFramebufferTexture(Shaders.dfb, GL30.GL_COLOR_ATTACHMENT0 + index,
                        readAlt.contains(index) ? target.main() : target.alt(), 0);
            }
            GL45.glNamedFramebufferTexture(Shaders.dfb, GL30.GL_DEPTH_ATTACHMENT, targets.depth(), 0);
            GlStateTracker.hostActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
        }

        @Override
        public void execute() {
            prepare();
            Shaders.useProgram(program);
            if (program.getCompositeMipmapSetting() != 0) Shaders.genCompositeMipmap();
            Shaders.preDrawComposite();
            Shaders.drawComposite();
            Shaders.postDrawComposite();
            var flips = Shaders.dfbColorTexturesFlip;
            var units = Shaders.colorTextureImageUnit;
            for (var index = 0; index < toggles.length; index++)
                if (toggles[index]) {
                    flips.flip(index);
                    bind(units[index], readAlt.contains(index) ? targets.get(index).main() : targets.get(index).alt());
                    GL45.glNamedFramebufferTexture(Shaders.dfb, GL30.GL_COLOR_ATTACHMENT0 + index,
                            readAlt.contains(index) ? targets.get(index).alt() : targets.get(index).main(), 0);
                }
            GlStateTracker.hostActiveTexture(GL13.GL_TEXTURE0);
        }
    }
}
