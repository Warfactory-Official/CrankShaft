package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlTextureLevelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GlTexture.class)
abstract class GlTextureMixin implements GlTextureLevelState {
    // GL defaults BASE_LEVEL to 0; MAX_LEVEL is set to mips-1 at creation -- MIN_VALUE marks "creation state",
    // resolved lazily against getMipLevels() by the encoder mixin.
    @Unique
    private int flywheel$base = 0;
    @Unique
    private int flywheel$max = Integer.MIN_VALUE;

    @Override
    public int flywheel$lastBase() {
        return flywheel$base;
    }

    @Override
    public void flywheel$lastBase(int value) {
        flywheel$base = value;
    }

    @Override
    public int flywheel$lastMax() {
        return flywheel$max;
    }

    @Override
    public void flywheel$lastMax(int value) {
        flywheel$max = value;
    }
}
