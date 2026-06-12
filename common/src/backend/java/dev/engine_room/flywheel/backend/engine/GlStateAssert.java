package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.FlwBackend;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Render-state desync hunting tool for the GL_NV mesh-shader terrain path.
 * <p>
 * Compares every value cached in Mojang's {@link GlStateManager} against the ACTUAL GL value (read via raw
 * lwjgl {@code glGet*} / {@code glIsEnabled}), logging every mismatch. Zero reflection: the private cache
 * fields are reached via direct field access (widened by crankshaft.accesswidener on Fabric and
 * accesstransformer.cfg on NeoForge). All driver reads use raw lwjgl entry points (NOT GlStateManager
 * getters) so probing does not perturb the very cache being audited.
 */
public final class GlStateAssert {
    private GlStateAssert() {
    }

    /**
     * Diff the whole GlStateManager cache against live GL. Logs one warn line per mismatch and returns the
     * mismatch count (0 = cache is coherent; at most one debug line is emitted in that case).
     *
     * @param label caller-supplied tag identifying the probe point (appears in every log line)
     * @return the number of cache/GL mismatches found
     */
    public static int assertRenderState(String label) {
        int mismatches = 0;

        // ---- Blend (index 0; func/equation are not exposed per-index without GL40) ----
        GlStateManager.BlendState blend = GlStateManager.BLEND[0];
        mismatches += checkBool(label, "BLEND[0].mode.enabled", blend.mode.enabled, GL11.glIsEnabled(GL11.GL_BLEND));
        mismatches += checkInt(label, "BLEND[0].srcRgb", blend.srcRgb, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));
        mismatches += checkInt(label, "BLEND[0].dstRgb", blend.dstRgb, GL11.glGetInteger(GL14.GL_BLEND_DST_RGB));
        mismatches += checkInt(label, "BLEND[0].srcAlpha", blend.srcAlpha, GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA));
        mismatches += checkInt(label, "BLEND[0].dstAlpha", blend.dstAlpha, GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
        mismatches += checkInt(label, "BLEND[0].modeRgb", blend.modeRgb, GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB));
        mismatches += checkInt(label, "BLEND[0].modeAlpha", blend.modeAlpha,
                GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA));

        // NOTE: GlStateManager models blend GLOBALLY -- every BLEND[i].mode wraps the single GL_BLEND cap, and only
        // BLEND[0] is actually maintained (e.g. Mojang's _disableBlend(0)). The BLEND[1-7] cache bits are vestigial,
        // so comparing them to per-attachment glIsEnabledi is a false positive (cache=false vs GL=true whenever
        // global blend is on). Only BLEND[0] vs the global GL_BLEND (checked above) is meaningful.

        // ---- Depth ----
        GlStateManager.DepthState depth = GlStateManager.DEPTH;
        mismatches += checkBool(label, "DEPTH.mode.enabled", depth.mode.enabled, GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        mismatches += checkBool(label, "DEPTH.mask", depth.mask, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        mismatches += checkInt(label, "DEPTH.func", depth.func, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));

        // ---- Cull ----
        mismatches += checkBool(label, "CULL.enable.enabled", GlStateManager.CULL.enable.enabled,
                GL11.glIsEnabled(GL11.GL_CULL_FACE));

        // ---- Polygon offset ----
        GlStateManager.PolygonOffsetState poly = GlStateManager.POLY_OFFSET;
        mismatches += checkBool(label, "POLY_OFFSET.fill.enabled", poly.fill.enabled,
                GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL));
        mismatches += checkFloat(label, "POLY_OFFSET.factor", poly.factor,
                GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR));
        mismatches += checkFloat(label, "POLY_OFFSET.units", poly.units, GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));

        // ---- Color logic op ----
        GlStateManager.ColorLogicState logic = GlStateManager.COLOR_LOGIC;
        mismatches += checkBool(label, "COLOR_LOGIC.enable.enabled", logic.enable.enabled,
                GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP));
        mismatches += checkInt(label, "COLOR_LOGIC.op", logic.op, GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE));

        // ---- Scissor ----
        mismatches += checkBool(label, "SCISSOR.mode.enabled", GlStateManager.SCISSOR.mode.enabled,
                GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));

        // ---- Color write mask (per-attachment; cache packs RGBA into a 4-bit mask) ----
        int[] colorMask = GlStateManager.COLOR_MASK;
        for (int i = 0; i < colorMask.length; i++) {
            int actual = readColorMaski(i);
            mismatches += checkInt(label, "COLOR_MASK[" + i + "]", colorMask[i], actual);
        }

        // ---- Framebuffer bindings GlStateManager itself tracks ----
        mismatches += checkInt(label, "readFbo", GlStateManager.readFbo,
                GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
        mismatches += checkInt(label, "writeFbo", GlStateManager.writeFbo,
                GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));

        // ---- Active texture unit (cache stores it 0-based; GL reports GL_TEXTURE0 + u) ----
        int savedActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        mismatches += checkInt(label, "activeTexture", GlStateManager.activeTexture, savedActive - GL13.GL_TEXTURE0);

        // ---- Per-unit GL_TEXTURE_2D bindings. Probe with raw glActiveTexture, restore at the end. ----
        GlStateManager.TextureState[] textures = GlStateManager.TEXTURES;
        for (int u = 0; u < textures.length; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            int actual = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            // The cache stores -1 for "unknown" (after a deleted texture); GL reports 0 for "none".
            int cached = textures[u].binding == -1 ? 0 : textures[u].binding;
            mismatches += checkInt(label, "TEXTURES[" + u + "].binding", cached, actual);
        }
        GL13.glActiveTexture(savedActive);

        if (mismatches == 0) {
            FlwBackend.LOGGER.debug("[glstate-assert] {} : cache coherent", label);
        }
        return mismatches;
    }

    /**
     * Dev-only tripwire: diff the GlStateManager cache against live GL and FAIL FAST if it is incoherent. Gate calls
     * behind {@code SharedConstants.IS_RUNNING_IN_IDE} so the diff (which issues raw {@code glGet*} reads) never runs
     * in production. A non-zero mismatch count means some engine raw-GL path left Mojang's state cache stale, which
     * silently corrupts a later Mojang pass -- surface it loudly during development.
     *
     * @param label caller-supplied tag identifying the tripwire point (appears in the exception + every warn line)
     */
    public static void assertCoherent(String label) {
        int n = assertRenderState(label);
        if (n > 0) {
            throw new IllegalStateException(
                    "[glstate-assert] " + label + ": " + n + " GlStateManager cache/GL mismatch(es) -- see warn log");
        }
    }

    private static int readColorMaski(int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer m = stack.malloc(4);
            GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, index, m);
            int packed = 0;
            if (m.get(0) != 0) {
                packed |= 1;
            }
            if (m.get(1) != 0) {
                packed |= 2;
            }
            if (m.get(2) != 0) {
                packed |= 4;
            }
            if (m.get(3) != 0) {
                packed |= 8;
            }
            return packed;
        }
    }

    private static int checkBool(String label, String name, boolean cached, boolean actual) {
        if (cached != actual) {
            FlwBackend.LOGGER.warn("[glstate-assert] {} : {} cached={} actual={}", label, name, cached, actual);
            return 1;
        }
        return 0;
    }

    private static int checkInt(String label, String name, int cached, int actual) {
        if (cached != actual) {
            FlwBackend.LOGGER.warn("[glstate-assert] {} : {} cached={} actual={}", label, name, cached, actual);
            return 1;
        }
        return 0;
    }

    private static int checkFloat(String label, String name, float cached, float actual) {
        if (cached != actual) {
            FlwBackend.LOGGER.warn("[glstate-assert] {} : {} cached={} actual={}", label, name, cached, actual);
            return 1;
        }
        return 0;
    }
}
