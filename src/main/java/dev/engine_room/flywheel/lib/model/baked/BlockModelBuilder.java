package dev.engine_room.flywheel.lib.model.baked;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.lib.model.EmptyModel;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 1.12.2: per-position multi-block region bake through Forge's lighter pipeline. Tint + per-face
 * lightmap baseline are baked; diffuse + AO come from the shader (see {@link LighterPipeline}).
 * The per-pos {@code translate(pos)} is composed into each quad's pose (the lighter outputs 0..1
 * model-local coords; we bake the integer pos in).
 * <p>
 * Fluid blocks (vanilla {@code BlockLiquid}) route to {@link FluidBakePipeline} when
 * {@link #renderFluids(boolean)} is enabled — they don't go through {@code IBakedModel} on 1.12.2.
 * Modded {@code BlockFluidBase} fluids use the regular {@code IBakedModel} path because they
 * register a {@code BakedFluid} model.
 */
public final class BlockModelBuilder {
    private final IBlockAccess level;
    private final Iterable<BlockPos> positions;
    private @Nullable Matrix4f pose;
    private boolean renderFluids = false;
    private @Nullable BlockMaterialFunction materialFunc;

    public BlockModelBuilder(IBlockAccess level, Iterable<BlockPos> positions) {
        this.level = level;
        this.positions = positions;
    }

    public BlockModelBuilder pose(@Nullable Matrix4f pose) {
        this.pose = pose;
        return this;
    }

    /** Include vanilla BlockLiquid (water / lava) via {@link FluidBakePipeline}. Modded Forge fluids
     *  (BlockFluidBase) are emitted via the regular IBakedModel path regardless of this flag. */
    public BlockModelBuilder renderFluids(boolean renderFluids) {
        this.renderFluids = renderFluids;
        return this;
    }

    public BlockModelBuilder materialFunc(@Nullable BlockMaterialFunction materialFunc) {
        this.materialFunc = materialFunc;
        return this;
    }

    public Model build() {
        BlockMaterialFunction func = materialFunc != null ? materialFunc : ModelUtil::getMaterial;
        Matrix3f normalMatrix = pose != null ? pose.normal(new Matrix3f()) : null;
        FluidBakePipeline fluidBaker = null;

        MeshAccumulator accumulator = new MeshAccumulator();
        LighterPipeline pipeline = new LighterPipeline(accumulator, Minecraft.getMinecraft().getBlockColors());
        Matrix4f scratchPose = new Matrix4f();

        for (BlockPos pos : positions) {
            IBlockState rawState = level.getBlockState(pos);
            if (rawState.getBlock().isAir(rawState, level, pos)) continue;

            IBlockState actualState;
            try {
                actualState = rawState.getActualState(level, pos);
            } catch (Exception ignored) {
                actualState = rawState;
            }

            EnumBlockRenderType renderType = actualState.getRenderType();
            if (renderType == EnumBlockRenderType.LIQUID) {
                if (renderFluids) {
                    if (fluidBaker == null) fluidBaker = new FluidBakePipeline();
                    BlockRenderLayer fluidLayer = actualState.getBlock().getRenderLayer();
                    // BlockFluidRenderer pre-multiplies per-face brightness (0.5 / 0.8 / 0.6 / 1.0)
                    // into the captured color. Request the unshaded material so the shader's
                    // cardinalLighting doesn't double-apply diffuse on top.
                    Material fluidMaterial = func.apply(fluidLayer, false, false);
                    if (fluidMaterial != null) {
                        Matrix4f effectivePose = composePose(pos, scratchPose);
                        ForgeHooksClient.setRenderLayer(fluidLayer);
                        try {
                            fluidBaker.bake(level, actualState, pos, accumulator, fluidMaterial,
                                    effectivePose, normalMatrix);
                        } finally {
                            ForgeHooksClient.setRenderLayer(null);
                        }
                    }
                }
                continue;
            }
            if (renderType != EnumBlockRenderType.MODEL) continue;

            // null -> missing model
            IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelForState(actualState);
            // extendedState is what getQuads receives — gives blocks access to connection /
            // surrounding info (fences, redstone wires, etc).
            IBlockState extendedState = actualState.getBlock().getExtendedState(actualState, level, pos);
            boolean ao = model.isAmbientOcclusion(extendedState);
            boolean useAo = ao && FlwConfig.INSTANCE.emissiveBlockAoBakingFix().shouldApplyAo(actualState.getLightValue(level, pos));

            Matrix4f effectivePose = composePose(pos, scratchPose);
            long seed = MathHelper.getPositionRandom(pos);

            pipeline.setupBlock(level, extendedState, pos);
            try {
                for (BlockRenderLayer layer : ModelUtil.LAYERS) {
                    if (!actualState.getBlock().canRenderInLayer(actualState, layer)) continue;
                    ForgeHooksClient.setRenderLayer(layer);
                    try {
                        emitBlock(model, actualState, extendedState, pos, layer, seed, func, useAo,
                                effectivePose, normalMatrix, pipeline);
                    } finally {
                        ForgeHooksClient.setRenderLayer(null);
                    }
                }
            } finally {
                pipeline.resetBlock();
            }
        }

        ImmutableList<Model.ConfiguredMesh> meshes = accumulator.build("BlockModel");
        if (meshes.isEmpty()) {
            return EmptyModel.INSTANCE;
        }
        return new SimpleModel(meshes);
    }

    /**
     * Compose the per-block translation (move quads from model-local 0..1 space to world-space pos)
     * with the optional caller-supplied pose. Reuses {@code scratch} to avoid per-block alloc.
     */
    private Matrix4f composePose(BlockPos pos, Matrix4f scratch) {
        if (pose == null) {
            scratch.identity().translate(pos.getX(), pos.getY(), pos.getZ());
        } else {
            scratch.set(pose).translate(pos.getX(), pos.getY(), pos.getZ());
        }
        return scratch;
    }

    private void emitBlock(IBakedModel model, IBlockState actualState, IBlockState extendedState,
                           BlockPos pos, BlockRenderLayer layer,
                           long seed, BlockMaterialFunction func, boolean useAo,
                           Matrix4f effectivePose, @Nullable Matrix3f normalMatrix,
                           LighterPipeline pipeline) {
        for (EnumFacing dir : ModelUtil.DIRECTIONS) {
            if (dir != null && !actualState.shouldSideBeRendered(level, pos, dir)) continue;

            List<BakedQuad> quads = model.getQuads(extendedState, dir, seed);
            for (BakedQuad quad : quads) {
                boolean shaded = quad.shouldApplyDiffuseLighting();
                Material material = func.apply(layer, shaded, useAo);
                if (material == null) continue;
                pipeline.bakeQuad(quad, material, effectivePose, normalMatrix);
            }
        }
    }
}
