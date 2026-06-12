package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GlTexture.class)
abstract class GlTextureMixin implements GlTextureLevelState {
    // GL defaults BASE_LEVEL to 0; MAX_LEVEL is set to mips-1 at creation -- MIN_VALUE marks "creation state",
    // resolved lazily against getMipLevels() by the encoder mixin.
    @Unique
    private int flw$base = 0;
    @Unique
    private int flw$max = Integer.MIN_VALUE;

    @Override
    public int flw$lastBase() {
        return flw$base;
    }

    @Override
    public void flw$lastBase(int value) {
        flw$base = value;
    }

    @Override
    public int flw$lastMax() {
        return flw$max;
    }

    @Override
    public void flw$lastMax(int value) {
        flw$max = value;
    }
}
