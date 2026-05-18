package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.IVertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 1.12.2: bridges Forge's {@code VertexLighterFlat} output (per-vertex-element float callbacks in
 * BLOCK format) into {@link MeshAccumulator}'s append path. The lighter pipeline is neutralized
 * for diffuse/AO (see {@link LighterPipeline}) — by the time data reaches {@code put()} it carries
 * only tint + per-face lightmap, which the shader composes with cardinalLighting diffuse and
 * {@code flw_light()}-sampled AO.
 * <p>
 * Caller contract: set the destination {@link Material} via
 * {@code beginQuad(Material, pose, normalMatrix)} BEFORE piping each quad. The consumer
 * accumulates 4 vertices' worth of {@code put()} calls and emits to the accumulator under that
 * material on the 4th vertex's last element. This is what gives upstream's per-quad material
 * routing: the {@code BlockMaterialFunction} is invoked per quad in the calling builder and the
 * result is set on the consumer before {@code quad.pipe(lighter)} runs.
 */
final class CapturingVertexConsumer implements IVertexConsumer {
    // BLOCK element indices match DefaultVertexFormats.BLOCK declaration: pos, color, uv, lightmap.
    private static final int E_POSITION = 0;
    private static final int E_COLOR = 1;
    private static final int E_UV = 2;
    private static final int E_LIGHTMAP = 3;
    private static final int LAST_ELEMENT = E_LIGHTMAP;

    private final MeshAccumulator accumulator;

    // Reusable per-quad staging — 4 vertices × per-element float arrays. Sized for max element
    // width (4 for color RGBA, 3 for position, 2 for uv/lightmap).
    private final float[][] positions = new float[4][3];
    private final float[][] colors = new float[4][4];
    private final float[][] uvs = new float[4][2];
    private final float[][] lightmaps = new float[4][2];

    @Nullable private Material currentMaterial;
    @Nullable private Matrix4f currentPose;
    @Nullable private Matrix3f currentNormalMatrix;
    private int currentVertex;

    CapturingVertexConsumer(MeshAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    /**
     * Set destination material + transforms for the next quad. Must be called before each
     * {@code quad.pipe(lighter)}.
     */
    void beginQuad(Material material, @Nullable Matrix4f pose, @Nullable Matrix3f normalMatrix) {
        this.currentMaterial = material;
        this.currentPose = pose;
        this.currentNormalMatrix = normalMatrix;
        this.currentVertex = 0;
    }

    @Override
    public VertexFormat getVertexFormat() {
        return DefaultVertexFormats.BLOCK;
    }

    @Override
    public void put(int element, float... data) {
        switch (element) {
            case E_POSITION:
                positions[currentVertex][0] = data[0];
                positions[currentVertex][1] = data[1];
                positions[currentVertex][2] = data[2];
                break;
            case E_COLOR:
                colors[currentVertex][0] = data[0];
                colors[currentVertex][1] = data[1];
                colors[currentVertex][2] = data[2];
                colors[currentVertex][3] = data.length > 3 ? data[3] : 1.0f;
                break;
            case E_UV:
                uvs[currentVertex][0] = data[0];
                uvs[currentVertex][1] = data[1];
                break;
            case E_LIGHTMAP:
                // VertexLighterFlat.updateLightmap writes lightmap[0]=block, lightmap[1]=sky
                // (both as (4bit*32)/65535). MeshAccumulator.appendLitQuad expects the same layout.
                lightmaps[currentVertex][0] = data[0];
                lightmaps[currentVertex][1] = data.length > 1 ? data[1] : 0f;
                break;
        }
        if (element == LAST_ELEMENT) {
            currentVertex++;
            if (currentVertex == 4) {
                accumulator.appendLitQuad(currentMaterial, positions, colors, uvs, lightmaps,
                        currentPose, currentNormalMatrix);
                currentVertex = 0;
            }
        }
    }

    // The lighter handles tint / orientation / diffuse internally — these arrive at the consumer
    // only as informational pass-throughs. Material + diffuse routing happened in the caller
    // before pipe(), so we don't need to retain them here.
    @Override public void setQuadTint(int tint) {}
    @Override public void setQuadOrientation(EnumFacing orientation) {}
    @Override public void setApplyDiffuseLighting(boolean diffuse) {}
    @Override public void setTexture(TextureAtlasSprite texture) {}
}
