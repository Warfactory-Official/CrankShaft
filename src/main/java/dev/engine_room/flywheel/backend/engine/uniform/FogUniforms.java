package dev.engine_room.flywheel.backend.engine.uniform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * 1.12.2: FogShape is hardcoded SPHERE (0) — 1.12.2 has no sphere/cylinder distinction.
 * Reads CPU-cached fog state instead of querying GL: {@code GlStateManager.fogState} tracks
 * mode/start/end/density, and {@code EntityRenderer} holds the live fog color. Vanilla uses EXP fog
 * (by density) underwater/in lava/in cloud fog and LINEAR (by start/end) everywhere else.
 */
public final class FogUniforms extends UniformWriter {
    private static final int SIZE = 4 * 9;
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

        int mode = GlStateManager.fogState.mode;
        ptr = writeInt(ptr, (mode == GL11.GL_EXP || mode == GL11.GL_EXP2) ? 1 : 0);
        ptr = writeFloat(ptr, GlStateManager.fogState.density);

        BUFFER.markDirty();
    }
}
