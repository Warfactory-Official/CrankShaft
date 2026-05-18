package dev.engine_room.flywheel.backend.engine.uniform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;

/**
 * 1.12.2: FogShape is hardcoded SPHERE (0) — 1.12.2 has no sphere/cylinder distinction.
 * Reads CPU-cached fog state instead of querying GL: {@code GlStateManager.fogState} tracks
 * start/end/density, and {@code EntityRenderer} holds the live fog color.
 */
public final class FogUniforms extends UniformWriter {
    private static final int SIZE = 4 * 7;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.FOG_INDEX, SIZE);

    private FogUniforms() {
    }

    public static void update() {
        long ptr = BUFFER.ptr();

        EntityRenderer er = Minecraft.getMinecraft().entityRenderer;
        ptr = writeFloat(ptr, er.fogColorRed);
        ptr = writeFloat(ptr, er.fogColorGreen);
        ptr = writeFloat(ptr, er.fogColorBlue);
        ptr = writeFloat(ptr, 1.0f);

        ptr = writeFloat(ptr, GlStateManager.fogState.start);
        ptr = writeFloat(ptr, GlStateManager.fogState.end);

        ptr = writeInt(ptr, 0);

        BUFFER.markDirty();
    }
}
