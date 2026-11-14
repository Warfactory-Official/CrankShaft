package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.backend.GpuTimer;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Client-thread feedback shared by both command registrations; status is sampled when a command executes.
 */
public final class FlwCommandText {
    private FlwCommandText() {
    }

    private static Component terrainName(TerrainMode mode) {
        return Component.translatable("flywheel.options.terrain." + mode.name().toLowerCase(Locale.ROOT));
    }

    public static Component terrainSet(TerrainMode mode) {
        return Component.translatable("command.flywheel.terrain.set", terrainName(mode));
    }

    public static Component terrainRequiresSodium(TerrainMode mode) {
        return Component.translatable("command.flywheel.terrain.requires_sodium", terrainName(mode));
    }

    public static void glOnlyFeedback(Consumer<Component> feedback) {
        if (VkContext.isVulkanHost()) feedback.accept(Component.translatable("command.flywheel.debug.gl_only"));
    }

    public static void gpuTimerState(GpuTimer.State state, Consumer<Component> feedback) {
        switch (state) {
            case ENABLED, DISABLED -> {
            }
            case ARMED -> feedback.accept(Component.translatable("command.flywheel.debug.gpu_timer.armed"));
            case UNAVAILABLE -> feedback.accept(Component.translatable("command.flywheel.debug.gpu_timer.unavailable"));
        }
    }
}
