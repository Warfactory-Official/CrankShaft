package dev.engine_room.flywheel.lib.model.baked;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.EmptyModel;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.12.2: quads go through Forge's VertexLighter pipeline for tint + per-face lightmap baseline.
 * Diffuse + AO are applied by the shader ({@code cardinalLightingMode} / {@code material.ambientOcclusion})
 * — see {@link LighterPipeline}. Per-quad material routing via {@code BlockMaterialFunction(layer, shaded, ao)}.
 * When level is null, falls back to {@code EmptyVirtualBlockGetter.FULL_DARK} (mesh is fully unlit —
 * callers typically override per-instance via {@code inst.light()}).
 */
public final class BakedModelBuilder {
    private static final Material DEFAULT_MATERIAL = SimpleMaterial.builderOf(Materials.CUTOUT)
            .texture(TextureMap.LOCATION_BLOCKS_TEXTURE)
            .build();

    private final IBakedModel model;
    private @Nullable IBlockState state;
    private long seed = 42L;
    private @Nullable Matrix4f pose;
    private @Nullable IBlockAccess tintWorld;
    private @Nullable BlockPos tintPos;
    private @Nullable BlockMaterialFunction materialFunc;
    private @Nullable Material singleMaterial;
    private @Nullable BlockRenderLayer forcedLayer;

    public BakedModelBuilder(IBakedModel model) {
        this.model = model;
    }

    public static BakedModelBuilder create(IBakedModel model) {
        return new BakedModelBuilder(model);
    }

    public BakedModelBuilder state(@Nullable IBlockState state) {
        this.state = state;
        return this;
    }

    public BakedModelBuilder seed(long seed) {
        this.seed = seed;
        return this;
    }

    public BakedModelBuilder pose(@Nullable Matrix4f pose) {
        this.pose = pose;
        return this;
    }

    public BakedModelBuilder tintContext(@Nullable IBlockAccess world, @Nullable BlockPos pos) {
        this.tintWorld = world;
        this.tintPos = pos;
        return this;
    }

    /** Single material for every emitted quad. Mutually exclusive with {@link #materialFunc}. */
    public BakedModelBuilder material(Material material) {
        this.singleMaterial = material;
        this.materialFunc = null;
        return this;
    }

    /** Route quads per (layer, shaded, ao) — return {@code null} to drop a quad. */
    public BakedModelBuilder materialFunc(BlockMaterialFunction materialFunc) {
        this.materialFunc = materialFunc;
        this.singleMaterial = null;
        return this;
    }

    /** Force a single render layer instead of deriving from state via {@code canRenderInLayer}. */
    public BakedModelBuilder layer(BlockRenderLayer layer) {
        this.forcedLayer = layer;
        return this;
    }

    public Model build() {
        BlockMaterialFunction func = resolveFunc();
        BlockRenderLayer[] layers = resolveLayers();
        IBlockState lighterState = state != null ? state : Blocks.AIR.getDefaultState();
        IBlockAccess level = tintWorld != null ? tintWorld : EmptyVirtualBlockGetter.FULL_DARK;
        BlockPos pos = tintPos != null ? tintPos : BlockPos.ORIGIN;
        Matrix3f normalMatrix = pose != null ? pose.normal(new Matrix3f()) : null;
        boolean ao = state != null ? model.isAmbientOcclusion(state) : model.isAmbientOcclusion();
        // useAo mirrors vanilla's BlockModelRenderer.renderModel: only when model wants it AND the
        // block isn't self-emissive. Player AO setting intentionally NOT checked — bakes should be
        // consistent across the toggle. Emitter threshold delegated to EmissiveBlockAoBakingFix (MC-225516).
        boolean useAo = ao && FlwConfig.INSTANCE.emissiveBlockAoBakingFix()
                .shouldApplyAo(lighterState.getLightValue(level, pos));

        MeshAccumulator accumulator = new MeshAccumulator();
        LighterPipeline pipeline = new LighterPipeline(accumulator, Minecraft.getMinecraft().getBlockColors());
        pipeline.setupBlock(level, lighterState, pos);
        try {
            for (BlockRenderLayer currentLayer : layers) {
                ForgeHooksClient.setRenderLayer(currentLayer);
                try {
                    emitLayer(currentLayer, func, useAo, normalMatrix, pipeline);
                } finally {
                    ForgeHooksClient.setRenderLayer(null);
                }
            }
        } finally {
            pipeline.resetBlock();
        }

        ImmutableList<Model.ConfiguredMesh> meshes = accumulator.build("BakedModel");
        if (meshes.isEmpty()) {
            return EmptyModel.INSTANCE;
        }
        return new SimpleModel(meshes);
    }

    private void emitLayer(BlockRenderLayer layer, BlockMaterialFunction func, boolean useAo,
                           @Nullable Matrix3f normalMatrix, LighterPipeline pipeline) {
        for (EnumFacing dir : ModelUtil.DIRECTIONS) {
            List<BakedQuad> quads = model.getQuads(state, dir, seed);
            for (BakedQuad quad : quads) {
                boolean shaded = quad.shouldApplyDiffuseLighting();
                Material material = func.apply(layer, shaded, useAo);
                if (material == null) continue;
                pipeline.bakeQuad(quad, material, pose, normalMatrix);
            }
        }
    }

    private BlockMaterialFunction resolveFunc() {
        if (materialFunc != null) return materialFunc;
        Material m = singleMaterial != null ? singleMaterial : DEFAULT_MATERIAL;
        return (layer, shaded, ao) -> m;
    }

    private BlockRenderLayer[] resolveLayers() {
        if (forcedLayer != null) {
            return new BlockRenderLayer[]{forcedLayer};
        }
        if (state == null) {
            return new BlockRenderLayer[]{BlockRenderLayer.CUTOUT_MIPPED};
        }
        List<BlockRenderLayer> result = new ArrayList<>(4);
        for (BlockRenderLayer l : ModelUtil.LAYERS) {
            if (state.getBlock().canRenderInLayer(state, l)) {
                result.add(l);
            }
        }
        if (result.isEmpty()) {
            result.add(state.getBlock().getRenderLayer());
        }
        return result.toArray(new BlockRenderLayer[0]);
    }
}
