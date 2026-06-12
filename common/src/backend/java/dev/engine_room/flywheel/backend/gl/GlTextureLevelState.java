package dev.engine_room.flywheel.backend.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;

/**
 * Duck on Mojang's {@code GlTexture}: the last-applied BASE_LEVEL/MAX_LEVEL, so redundant bind-time
 * {@code glTexParameter} re-applies are skipped. Bindless handles freeze their textures, turning the redundant
 * pair into a per-bind GL_INVALID_OPERATION flood; the dedup also drops two GL calls from every classic bind.
 */
public interface GlTextureLevelState {
    static boolean shouldApplyBase(GlTexture texture, int value) {
        GlTextureLevelState state = (GlTextureLevelState) (Object) texture;
        if (state.flw$lastBase() == value) {
            return false;
        }
        state.flw$lastBase(value);
        return true;
    }

    static boolean shouldApplyMax(GlTexture texture, int value) {
        GlTextureLevelState state = (GlTextureLevelState) (Object) texture;
        int last = state.flw$lastMax();
        if (last == Integer.MIN_VALUE) {
            last = texture.getMipLevels() - 1;
        }
        state.flw$lastMax(value);
        return last != value;
    }

    static void applyMipLevels(GlTexture texture, int base, int max) {
        if (shouldApplyBase(texture, base)) {
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, base);
        }
        if (shouldApplyMax(texture, max)) {
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, max);
        }
    }

    int flw$lastBase();

    void flw$lastBase(int value);

    int flw$lastMax();

    void flw$lastMax(int value);
}
