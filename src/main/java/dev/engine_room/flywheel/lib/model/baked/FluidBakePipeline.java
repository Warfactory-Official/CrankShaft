package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 1.12.2 fluid bake pipeline. Vanilla {@code BlockLiquid} (water/lava) renders through
 * {@code BlockFluidRenderer}, which writes directly into a {@code BufferBuilder} in
 * {@code DefaultVertexFormats.BLOCK} — it does NOT go through {@code IBakedModel}. We hand vanilla
 * a {@code BufferBuilder} subclass that overrides the fluent write methods to capture data
 * straight into {@link MeshAccumulator}-shaped staging arrays (no {@code ByteBuffer} round-trip).
 * <p>
 * The 1.12.2 analogue of upstream Flywheel's {@code BakedModelBufferer} fluid path — upstream
 * wraps {@code renderDispatcher.renderLiquid}'s {@code VertexConsumer}; we subclass
 * {@code BufferBuilder} because 1.12.2 has no {@code VertexConsumer} interface that
 * {@code BlockFluidRenderer} accepts.
 * <p>
 * Modded Forge fluids ({@code BlockFluidBase} / {@code IFluidBlock}) keep
 * {@code EnumBlockRenderType.MODEL} and have a {@code BakedFluid} in the model registry — those
 * flow through the regular {@code IBakedModel} path and are NOT routed here.
 */
final class FluidBakePipeline extends BufferBuilder {
    private final BlockFluidRenderer fluidRenderer =
            new BlockFluidRenderer(Minecraft.getMinecraft().getBlockColors());

    private final float[][] positions = new float[4][3];
    private final float[][] colors = new float[4][4];
    private final float[][] uvs = new float[4][2];
    private final float[][] lightmaps = new float[4][2];

    private @Nullable MeshAccumulator currentAccumulator;
    private @Nullable Material currentMaterial;
    private @Nullable Matrix4f currentPose;
    private @Nullable Matrix3f currentNormalMatrix;
    // Shift world-coord pos() writes back to block-local 0..1 so the caller-supplied pose (which
    // already composes T(blockPos) * user_pose) lands quads in the same coord system as the
    // IBakedModel path. Applied in our pos() override; we ignore the parent's xOffset/yOffset/zOffset.
    private double xOffset;
    private double yOffset;
    private double zOffset;
    private int currentVertex;

    FluidBakePipeline() {
        // 1 int (4 bytes) — the minimum direct ByteBuffer the parent will allocate. Never written to
        // because every fluent method that touches the byte buffer is overridden below.
        super(1);
    }

    /**
     * Bake one vanilla liquid block: invoke {@link BlockFluidRenderer#renderFluid} with this as the
     * destination buffer. Each captured quad (4 endVertex calls) is appended to {@code accumulator}
     * under {@code material} with {@code pose} / {@code normalMatrix} applied.
     */
    void bake(IBlockAccess level, IBlockState state, BlockPos pos, MeshAccumulator accumulator,
              Material material, @Nullable Matrix4f pose, @Nullable Matrix3f normalMatrix) {
        this.currentAccumulator = accumulator;
        this.currentMaterial = material;
        this.currentPose = pose;
        this.currentNormalMatrix = normalMatrix;
        this.xOffset = -pos.getX();
        this.yOffset = -pos.getY();
        this.zOffset = -pos.getZ();
        this.currentVertex = 0;
        try {
            fluidRenderer.renderFluid(level, state, pos, this);
        } finally {
            this.currentAccumulator = null;
            this.currentMaterial = null;
            this.currentPose = null;
            this.currentNormalMatrix = null;
        }
    }

    @Override
    public BufferBuilder pos(double x, double y, double z) {
        positions[currentVertex][0] = (float) (x + xOffset);
        positions[currentVertex][1] = (float) (y + yOffset);
        positions[currentVertex][2] = (float) (z + zOffset);
        return this;
    }

    @Override
    public BufferBuilder color(float red, float green, float blue, float alpha) {
        colors[currentVertex][0] = red;
        colors[currentVertex][1] = green;
        colors[currentVertex][2] = blue;
        colors[currentVertex][3] = alpha;
        return this;
    }

    @Override
    public BufferBuilder color(int red, int green, int blue, int alpha) {
        colors[currentVertex][0] = red / 255f;
        colors[currentVertex][1] = green / 255f;
        colors[currentVertex][2] = blue / 255f;
        colors[currentVertex][3] = alpha / 255f;
        return this;
    }

    @Override
    public BufferBuilder tex(double u, double v) {
        uvs[currentVertex][0] = (float) u;
        uvs[currentVertex][1] = (float) v;
        return this;
    }

    @Override
    public BufferBuilder lightmap(int skyLight, int blockLight) {
        // BlockFluidRenderer passes the high/low halves of getPackedLightmapCoords directly:
        // each arg is (4bit_light << 4) in 0..240. MeshAccumulator's appendLitQuad expects floats
        // in (4bit * 32) / 65535 form (matches VertexLighterFlat output) — cancel the <<4, scale by
        // 32/65535 → ×2/65535. Block goes to slot 0, sky to slot 1 per the consumer contract.
        lightmaps[currentVertex][0] = blockLight * 2f / 65535f;
        lightmaps[currentVertex][1] = skyLight * 2f / 65535f;
        return this;
    }

    @Override
    public void endVertex() {
        currentVertex++;
        if (currentVertex == 4) {
            currentAccumulator.appendLitQuad(currentMaterial, positions, colors, uvs, lightmaps,
                    currentPose, currentNormalMatrix);
            currentVertex = 0;
        }
    }
}
