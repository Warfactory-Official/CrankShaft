package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.lib.compat.BiomeBlendCompat;
import net.minecraft.client.settings.GameSettings;

/**
 * 1.12.2: slots with no vanilla 1.12.2 analog are sourced from {@link FlwConfig}; biomeBlend is
 * sourced from the first available Sodium-derived fork (see {@link BiomeBlendCompat}).
 */
public final class OptionsUniforms extends UniformWriter {
    private static final int SIZE = 4 * 14;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.OPTIONS_INDEX, SIZE);

    private OptionsUniforms() {
    }

    public static void update(GameSettings options) {
        FlwConfig cfg = FlwConfig.INSTANCE;
        long ptr = BUFFER.ptr();

        ptr = writeFloat(ptr, options.gammaSetting);
        ptr = writeInt(ptr, options.fovSetting != 0 ? (int) options.fovSetting : 70);
        ptr = writeFloat(ptr, cfg.distortionOption());
        ptr = writeFloat(ptr, cfg.glintSpeedOption());
        ptr = writeFloat(ptr, cfg.glintStrengthOption());
        ptr = writeInt(ptr, BiomeBlendCompat.RADIUS.getAsInt());
        ptr = writeInt(ptr, options.ambientOcclusion != 0 ? 1 : 0);
        ptr = writeInt(ptr, options.viewBobbing ? 1 : 0);
        ptr = writeInt(ptr, cfg.highContrastOption() ? 1 : 0);
        ptr = writeFloat(ptr, cfg.textBackgroundOpacityOption());
        ptr = writeInt(ptr, cfg.textBackgroundForChatOnlyOption() ? 1 : 0);
        ptr = writeFloat(ptr, cfg.darknessPulsingOption());
        ptr = writeFloat(ptr, cfg.damageTiltOption());
        ptr = writeInt(ptr, cfg.hideLightningFlashesOption() ? 1 : 0);

        BUFFER.markDirty();
    }
}
