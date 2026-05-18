package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 1.12.2: Forge's {@code VertexLighterFlat} with diffuse baking neutralized. Diffuse is applied by
 * the shader via {@code material.cardinalLightingMode}, and AO is sampled at runtime by
 * {@code flw_light()} (gated by {@code material.ambientOcclusion}) — so the lighter must NOT
 * pre-bake either. {@code SmoothAo} is dropped for the same reason. The lighter still handles
 * tint multiplication and per-face lightmap baseline (the shader's {@code flw_light} {@code max()}es
 * smooth lighting on top).
 */
final class LighterPipeline {
    private final CapturingVertexConsumer consumer;
    private final NoBakeLighter lighter;

    LighterPipeline(MeshAccumulator accumulator, BlockColors colors) {
        this.consumer = new CapturingVertexConsumer(accumulator);
        this.lighter = new NoBakeLighter(colors);
        // setParent triggers setVertexFormat which allocates the lighter's gathering arrays.
        // Call once and never again to avoid per-bake realloc churn.
        this.lighter.setParent(consumer);
    }

    void setupBlock(IBlockAccess level, IBlockState state, BlockPos pos) {
        lighter.setWorld(level);
        lighter.setState(state);
        lighter.setBlockPos(pos);
        lighter.updateBlockInfo();
    }

    void resetBlock() {
        lighter.resetBlockInfo();
    }

    void bakeQuad(BakedQuad quad, Material material, @Nullable Matrix4f pose,
                  @Nullable Matrix3f normalMatrix) {
        consumer.beginQuad(material, pose, normalMatrix);
        quad.pipe(lighter);
    }

    private static final class NoBakeLighter extends VertexLighterFlat {
        NoBakeLighter(BlockColors colors) {
            super(colors);
            super.setApplyDiffuseLighting(false);
        }

        @Override
        public void setApplyDiffuseLighting(boolean diffuse) {
        }
    }
}
