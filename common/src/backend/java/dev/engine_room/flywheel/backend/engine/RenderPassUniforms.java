package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Util;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;

/**
 * Per-frame UBO ring buffers backing the RenderPass producer's fragment-side lighting (upstream common.frag
 * parity): a per-draw-group packed material ({@code _FlwInstanceDraw}) + a per-frame render origin
 * ({@code _FlwRenderOrigin}), plus the embedded-environment per-draw UBOs ({@code _FlwEmbed} /
 * {@code _FlwEmbedDraw}). All are CPU-mapped triple-buffered rings ({@link DynamicUniformStorage}); the 26.2
 * RenderPass RHI has no scalar uniform setter, so these small values ride UBO slices bound per draw via
 * {@code pass.setUniform}. Slices are pre-written (before the pass) and stay valid until the next frame's
 * {@link #beginFrame}. One instance per draw manager.
 */
public final class RenderPassUniforms {
    // DynamicUniformStorage rounds the block to the device's min uniform-offset alignment,
    // so the small declared sizes are just the written-byte counts.
    private final DynamicUniformStorage<MaterialUniform> material = new DynamicUniformStorage<>("flywheel:material", 20,
            64);
    private final DynamicUniformStorage<RenderOriginUniform> renderOrigin = new DynamicUniformStorage<>(
            "flywheel:render_origin", 20, 2);
    // Embedded-environment per-draw UBOs: instancing carries the composed pose/normal directly (mat4 + mat4 =
    // 128 B); indirect carries only the MultiDraw's draw-command start offset (the matrix rides the SSBO).
    private final DynamicUniformStorage<EmbedUniform> embed = new DynamicUniformStorage<>("flywheel:embed", 128, 16);
    private final DynamicUniformStorage<EmbedDrawUniform> embedDraw = new DynamicUniformStorage<>("flywheel:embed_draw",
            4, 16);

    private GpuBufferSlice renderOriginSlice;
    // Frame-constant glint inputs the per-material vertex shaders read; written into every _FlwInstanceDraw
    // slice (the RenderPass port has no flywheel frame/options UBO -- see header.vsh).
    private float frameSystemSeconds;
    private float frameGlintSpeedOption;
    private float frameGlintStrengthOption;

    /**
     * Rotate the rings for a new frame and write this frame's render origin (constant across every pass, so it
     * is written once here and reused via {@link #renderOriginSlice}). Call once per frame, before any pass.
     */
    public void beginFrame(Vec3i origin, boolean constantAmbientLight) {
        material.endFrame();
        renderOrigin.endFrame();
        embed.endFrame();
        embedDraw.endFrame();
        renderOriginSlice = renderOrigin.writeUniform(
                new RenderOriginUniform(origin.getX(), origin.getY(), origin.getZ(), constantAmbientLight ? 1 : 0));
        // Glint animation inputs, constant across the frame (upstream FrameUniforms.writeTime +
        // OptionsUniforms parity: Util.getMillis()/1000 + the glintSpeed accessibility option).
        frameSystemSeconds = Util.getMillis() / 1000f;
        frameGlintSpeedOption = Minecraft.getInstance().options.glintSpeed().get().floatValue();
        frameGlintStrengthOption = Minecraft.getInstance().options.glintStrength().get().floatValue();
    }

    public GpuBufferSlice renderOriginSlice() {
        return renderOriginSlice;
    }

    /**
     * A UBO slice carrying {@code packedProperties} as {@code _flw_drawPackedMaterial.y}, plus this frame's
     * {@code flw_systemSeconds}/{@code flw_glintSpeedOption}/{@code flw_glintStrengthOption} (read by the
     * per-material glint vertex shaders).
     */
    public GpuBufferSlice material(int packedProperties) {
        return material.writeUniform(new MaterialUniform(packedProperties, frameSystemSeconds, frameGlintSpeedOption,
                frameGlintStrengthOption));
    }

    /**
     * Instancing embed: the composed pose/normal for one embedded draw ({@code _FlwEmbed}).
     */
    public GpuBufferSlice embed(Matrix4fc pose, Matrix3fc normal) {
        return embed.writeUniform(new EmbedUniform(new Matrix4f(pose), new Matrix4f(normal)));
    }

    /**
     * Indirect embed: the MultiDraw's start offset into the draw-command buffer ({@code _FlwEmbedDraw}).
     */
    public GpuBufferSlice embedDraw(int baseDraw) {
        return embedDraw.writeUniform(new EmbedDrawUniform(baseDraw));
    }

    public void delete() {
        material.close();
        renderOrigin.close();
        embed.close();
        embedDraw.close();
    }

    private record MaterialUniform(int packedProperties, float systemSeconds, float glintSpeedOption,
                                   float glintStrengthOption) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buf) {
            buf.putInt(
                    0);                     // _flw_drawPackedMaterial.x (packedFogAndCutout -- unused by the fragment)
            buf.putInt(packedProperties);      // _flw_drawPackedMaterial.y
            buf.putFloat(systemSeconds);       // flw_systemSeconds
            buf.putFloat(glintSpeedOption);    // flw_glintSpeedOption
            buf.putFloat(glintStrengthOption); // flw_glintStrengthOption
        }
    }

    private record RenderOriginUniform(int x, int y, int z,
                                       int constantAmbientLight) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buf) {
            buf.putInt(x).putInt(y).putInt(z).putInt(0); // ivec4 _flw_renderOrigin (w padding)
            buf.putInt(constantAmbientLight);            // uint _flw_constantAmbientLight
        }
    }

    // The normal mat3 rides the upper-left of a mat4 (std140 simplicity); the shader's mat3(_flw_embedNormal)
    // extracts the 3x3. Copies guard against the environment mutating its composed matrices next frame.
    private record EmbedUniform(Matrix4f pose, Matrix4f normal) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buf) {
            pose.get(0, buf);    // mat4 _flw_embedPose (column-major)
            normal.get(64, buf); // mat4 _flw_embedNormal
        }
    }

    private record EmbedDrawUniform(int baseDraw) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buf) {
            buf.putInt(baseDraw); // uint _flw_baseDraw
        }
    }
}
