package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

public final class BlockModels {
    public static final BlockMaterialFunction DEFAULT_MATERIAL_FUNCTION = BlockModels::defaultMaterial;

    private static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private static final Material[] DEFAULT_MATERIALS;

    static {
        DEFAULT_MATERIALS = new Material[LAYERS.length * 2];
        bind(BlockRenderLayer.SOLID, Materials.SOLID_BLOCK, Materials.SOLID_UNSHADED_BLOCK);
        bind(BlockRenderLayer.CUTOUT_MIPPED, Materials.CUTOUT_MIPPED_BLOCK, Materials.CUTOUT_MIPPED_UNSHADED_BLOCK);
        bind(BlockRenderLayer.CUTOUT, Materials.CUTOUT_BLOCK, Materials.CUTOUT_UNSHADED_BLOCK);
        bind(BlockRenderLayer.TRANSLUCENT, Materials.TRANSLUCENT_BLOCK, Materials.TRANSLUCENT_UNSHADED_BLOCK);
    }

    private static void bind(BlockRenderLayer layer, Material shaded, Material unshaded) {
        int base = layer.ordinal() * 2;
        DEFAULT_MATERIALS[base] = withBlockAtlas(unshaded);
        DEFAULT_MATERIALS[base + 1] = withBlockAtlas(shaded);
    }

    private static Material withBlockAtlas(Material base) {
        return SimpleMaterial.builderOf(base)
                .texture(TextureMap.LOCATION_BLOCKS_TEXTURE)
                .build();
    }

    private static Material defaultMaterial(BlockRenderLayer layer, boolean shaded) {
        return DEFAULT_MATERIALS[layer.ordinal() * 2 + (shaded ? 1 : 0)];
    }

    private static final RendererReloadCache<IBlockState, Model> MODEL_CACHE =
            new RendererReloadCache<>(state -> get(state, DEFAULT_MATERIAL_FUNCTION));

    // Fake IBlockAccess returning plains-biome and air everywhere so biome-driven tints
    // (grass, leaves) collapse to a single plains color per state. One cache entry per
    // IBlockState; biome-correctness is traded for cache hits (same tradeoff as upstream).
    private static final IBlockAccess FAKE_PLAINS_ACCESS = new IBlockAccess() {
        @Override @Nullable public TileEntity getTileEntity(BlockPos pos) { return null; }
        @Override public int getCombinedLight(BlockPos pos, int lightValue) { return 0; }
        @Override public IBlockState getBlockState(BlockPos pos) { return Blocks.AIR.getDefaultState(); }
        @Override public boolean isAirBlock(BlockPos pos) { return true; }
        @Override public Biome getBiome(BlockPos pos) { return Biomes.PLAINS; }
        @Override public int getStrongPower(BlockPos pos, EnumFacing direction) { return 0; }
        @Override public WorldType getWorldType() { return WorldType.DEFAULT; }
        @Override public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) { return _default; }
    };

    private BlockModels() {
    }

    public static Model get(IBlockState state) {
        return MODEL_CACHE.get(state);
    }

    public static Model get(IBlockState state, BlockMaterialFunction materialFunc) {
        IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(state);
        Block block = state.getBlock();

        List<Model.ConfiguredMesh> meshes = new ArrayList<>();
        BlockRenderLayer originalLayer = MinecraftForgeClient.getRenderLayer();
        try {
            List<BakedQuad> shadedBucket = new ArrayList<>();
            List<BakedQuad> unshadedBucket = new ArrayList<>();
            for (BlockRenderLayer layer : LAYERS) {
                if (!block.canRenderInLayer(state, layer)) continue;
                ForgeHooksClient.setRenderLayer(layer);

                shadedBucket.clear();
                unshadedBucket.clear();
                for (EnumFacing dir : ModelUtil.DIRECTIONS) {
                    // Mirrors upstream's fixed bake seed; vanilla 1.12.2 uses getPositionRandom/0L instead.
                    for (BakedQuad quad : model.getQuads(state, dir, 42L)) {
                        (quad.shouldApplyDiffuseLighting() ? shadedBucket : unshadedBucket).add(quad);
                    }
                }

                emitBucket(meshes, materialFunc, layer, true, shadedBucket, state);
                emitBucket(meshes, materialFunc, layer, false, unshadedBucket, state);
            }
        } finally {
            ForgeHooksClient.setRenderLayer(originalLayer);
        }

        if (meshes.isEmpty()) return EmptyModel.INSTANCE;
        return new SimpleModel(meshes);
    }

    private static void emitBucket(List<Model.ConfiguredMesh> out, BlockMaterialFunction materialFunc,
                                   BlockRenderLayer layer, boolean shaded, List<BakedQuad> quads, IBlockState state) {
        if (quads.isEmpty()) return;
        Material material = materialFunc.apply(layer, shaded);
        if (material == null) return;
        Mesh mesh = bakeMeshFromQuads(quads, state, layer, shaded);
        out.add(new Model.ConfiguredMesh(material, mesh));
    }

    private static Mesh bakeMeshFromQuads(List<BakedQuad> quads, IBlockState state, BlockRenderLayer layer, boolean shaded) {
        int vertexCount = quads.size() * 4;

        MemoryBlock memoryBlock = MemoryBlock.mallocTracked(vertexCount * FullVertexView.STRIDE);
        FullVertexView meshVertices = new FullVertexView();
        meshVertices.nativeMemoryOwner(memoryBlock);
        meshVertices.ptr(memoryBlock.ptr());
        meshVertices.vertexCount(vertexCount);

        Vector3f normal = new Vector3f();

        try (MemoryStack memoryStack = MemoryStack.stackPush()) {
            // 1.12.2 BLOCK format: 28 bytes (7 ints); we only read bytes 0..23 (pos + UV).
            ByteBuffer byteBuffer = memoryStack.malloc(28);
            IntBuffer intBuffer = byteBuffer.asIntBuffer();

            int vertex = 0;
            for (BakedQuad quad : quads) {
                int[] vertexData = quad.getVertexData();
                int stride = vertexData.length / 4;
                EnumFacing face = quad.getFace();
                Vec3i faceVec = face.getDirectionVec();
                normal.set(faceVec.getX(), faceVec.getY(), faceVec.getZ());

                int tintIndex = quad.getTintIndex();
                float r = 1.0f, g = 1.0f, b = 1.0f;
                if (tintIndex >= 0) {
                    int packed = Minecraft.getMinecraft().getBlockColors()
                            .colorMultiplier(state, FAKE_PLAINS_ACCESS, BlockPos.ORIGIN, tintIndex);
                    r = ((packed >> 16) & 0xFF) / 255.0f;
                    g = ((packed >>  8) & 0xFF) / 255.0f;
                    b = ( packed        & 0xFF) / 255.0f;
                }

                for (int v = 0; v < 4; v++) {
                    intBuffer.clear();
                    intBuffer.put(vertexData, v * stride, Math.min(stride, 7));

                    meshVertices.x(vertex, byteBuffer.getFloat(0));
                    meshVertices.y(vertex, byteBuffer.getFloat(4));
                    meshVertices.z(vertex, byteBuffer.getFloat(8));
                    meshVertices.r(vertex, r);
                    meshVertices.g(vertex, g);
                    meshVertices.b(vertex, b);
                    meshVertices.a(vertex, 1.0f);
                    meshVertices.u(vertex, byteBuffer.getFloat(16));
                    meshVertices.v(vertex, byteBuffer.getFloat(20));
                    meshVertices.overlay(vertex, OverlayTexture.NO_OVERLAY);
                    meshVertices.light(vertex, 0);
                    meshVertices.normalX(vertex, normal.x);
                    meshVertices.normalY(vertex, normal.y);
                    meshVertices.normalZ(vertex, normal.z);

                    vertex++;
                }
            }
        }

        return new SimpleQuadMesh(meshVertices, "BlockModel[" + state + ",layer=" + layer + (shaded ? ",shaded" : ",unshaded") + "]");
    }
}
