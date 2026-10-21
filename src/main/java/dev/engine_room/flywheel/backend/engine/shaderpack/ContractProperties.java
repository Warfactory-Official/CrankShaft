package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Transparency;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import java.util.Locale;
import java.util.Properties;

final class ContractProperties {
    final Properties values;

    ContractProperties(Properties values) {
        this.values = new Properties();
        values.forEach((key, value) -> this.values.setProperty(key.toString().strip(), value.toString().strip()));
    }

    private static Blend parseBlend(String value, Blend fallback) {
        if (value == null) return fallback;
        if (value.strip().equalsIgnoreCase("off")) return Blend.OFF;
        var fields = value.strip().toUpperCase(Locale.ROOT).split("\\s+");
        if (fields.length != 4) throw new IllegalArgumentException("Colorwheel blend needs four factors: " + value);
        var factors = new int[4];
        for (var index = 0; index < fields.length; index++) {
            factors[index] = switch (fields[index]) {
                case "ZERO" -> GL11.GL_ZERO;
                case "ONE" -> GL11.GL_ONE;
                case "SRC_COLOR" -> GL11.GL_SRC_COLOR;
                case "ONE_MINUS_SRC_COLOR" -> GL11.GL_ONE_MINUS_SRC_COLOR;
                case "DST_COLOR" -> GL11.GL_DST_COLOR;
                case "ONE_MINUS_DST_COLOR" -> GL11.GL_ONE_MINUS_DST_COLOR;
                case "SRC_ALPHA" -> GL11.GL_SRC_ALPHA;
                case "ONE_MINUS_SRC_ALPHA" -> GL11.GL_ONE_MINUS_SRC_ALPHA;
                case "DST_ALPHA" -> GL11.GL_DST_ALPHA;
                case "ONE_MINUS_DST_ALPHA" -> GL11.GL_ONE_MINUS_DST_ALPHA;
                case "SRC_ALPHA_SATURATE" -> GL11.GL_SRC_ALPHA_SATURATE;
                case "CONSTANT_COLOR" -> GL14.GL_CONSTANT_COLOR;
                case "ONE_MINUS_CONSTANT_COLOR" -> GL14.GL_ONE_MINUS_CONSTANT_COLOR;
                case "CONSTANT_ALPHA" -> GL14.GL_CONSTANT_ALPHA;
                case "ONE_MINUS_CONSTANT_ALPHA" -> GL14.GL_ONE_MINUS_CONSTANT_ALPHA;
                default -> throw new IllegalArgumentException("Unknown Colorwheel blend factor " + fields[index]);
            };
        }
        return new Blend(true, factors[0], factors[1], factors[2], factors[3]);
    }

    boolean shadowEnabled() {
        return Boolean.parseBoolean(values.getProperty("shadow.enabled", "true"));
    }

    Blend blend(ContractProgram program, int buffer, Blend defaultBlend) {
        var path = "blend." + program.sourceName;
        var global = values.getProperty(path);
        var local = values.getProperty(
                path + (program.sourceName.startsWith("clrwl_shadow") ? ".shadowcolor" : ".colortex") + buffer);
        return parseBlend(local != null ? local : global, defaultBlend);
    }

    record Blend(boolean enabled, int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
        static final Blend OFF = new Blend(false, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);

        static Blend material(Transparency transparency) {
            return switch (transparency) {
                case OPAQUE -> OFF;
                case TRANSLUCENT, ORDER_INDEPENDENT ->
                        new Blend(true, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                                GL11.GL_ONE_MINUS_SRC_ALPHA);
                case ADDITIVE -> new Blend(true, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
                case LIGHTNING -> new Blend(true, GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                case GLINT -> new Blend(true, GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
                case CRUMBLING -> new Blend(true, GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ZERO);
            };
        }

        static Blend capture(int slot) {
            return new Blend(GL30.glIsEnabledi(GL11.GL_BLEND, slot),
                    GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB, slot), GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB, slot),
                    GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA, slot),
                    GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA, slot));
        }

        void apply(int slot) {
            // Indexed state also changes slot zero; keep Minecraft's global blend cache truthful for later passes.
            if (slot == 0) {
                if (enabled) GlStateManager.enableBlend();
                else GlStateManager.disableBlend();
                GlStateManager.tryBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            }
            if (enabled) GL30.glEnablei(GL11.GL_BLEND, slot);
            else GL30.glDisablei(GL11.GL_BLEND, slot);
            GL40.glBlendFuncSeparatei(slot, sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GL40.glBlendEquationSeparatei(slot, GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        }
    }
}
