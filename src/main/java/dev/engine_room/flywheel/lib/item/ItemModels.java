package dev.engine_room.flywheel.lib.item;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.compat.animation.SmartAnimatedTextureCompat;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.*;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import it.unimi.dsi.fastutil.objects.ReferenceArraySet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ItemModels {
    private static final TintColors NO_TINTS = new TintColors(new int[0]);

    private static final RendererReloadCache<BakedModelKey, Model> MODEL_CACHE =
            new RendererReloadCache<>(key -> bakeModel(key.model(), key.transformType(), key.material(), key.tints(), key.foil()));

    // Reused across all getForModel calls. SimpleMaterial uses Object identity for equals, so
    // building a fresh Material per call made BakedModelKey miss the cache every time, leaking
    // a TrackedMemoryBlock per item-model bake.
    private static final Material ITEM_MATERIAL = SimpleMaterial.builderOf(Materials.CUTOUT)
            .texture(TextureMap.LOCATION_BLOCKS_TEXTURE)
            .cardinalLightingMode(CardinalLightingMode.ENTITY)
            // useOverlay = false: 1.12.2 has no overlay texture, and the unbound overlay sampler
            // (TEXTURE1 — same unit as the lightmap) would otherwise mix a faint darkening into
            // the final color via the fragment shader's overlay-mix branch.
            .useOverlay(false)
            .build();

    private ItemModels() {
    }

    public static IBakedModel getModel(ItemStack stack) {
        return Minecraft.getMinecraft().getRenderItem().getItemModelMesher().getItemModel(stack);
    }

    // Returns null for built-in renderers, which bypass the baked-quad path.
    @Nullable
    public static IBakedModel getActualBakedModel(@Nullable World level, ItemStack stack, TransformType displayContext) {
        if (stack.isEmpty()) return null;
        IBakedModel baseModel = getModel(stack);
        ItemOverrideList overrides = baseModel.getOverrides();
        IBakedModel model = overrides.handleItemState(baseModel, stack, level, null);
        if (model.isBuiltInRenderer()) return null;
        return model;
    }

    public static boolean isSupported(ItemStack stack) {
        if (stack.isEmpty()) return false;
        IBakedModel model = getModel(stack);
        return !model.isBuiltInRenderer();
    }

    public static Model get(@Nullable World level, ItemStack stack, TransformType displayContext) {
        IBakedModel model = getActualBakedModel(level, stack, displayContext);
        if (model == null) return EmptyModel.INSTANCE;
        return getForModel(model, displayContext, resolveTintColors(model, stack), stack.hasEffect());
    }

    public static Model getForModel(IBakedModel model, TransformType displayContext) {
        return getForModel(model, displayContext, NO_TINTS, false);
    }

    private static Model getForModel(IBakedModel model, TransformType displayContext, TintColors tints, boolean foil) {
        return MODEL_CACHE.get(new BakedModelKey(model, displayContext, ITEM_MATERIAL, tints, foil));
    }

    private static TintColors resolveTintColors(IBakedModel model, ItemStack stack) {
        int maxTintIndex = -1;
        for (EnumFacing dir : ModelUtil.DIRECTIONS) {
            for (BakedQuad q : model.getQuads(null, dir, 42L)) {
                int t = q.getTintIndex();
                if (t > maxTintIndex) maxTintIndex = t;
            }
        }
        if (maxTintIndex < 0) return NO_TINTS;
        int[] colors = new int[maxTintIndex + 1];
        ItemColors itemColors = Minecraft.getMinecraft().getItemColors();
        for (int i = 0; i <= maxTintIndex; i++) {
            colors[i] = itemColors.colorMultiplier(stack, i) | 0xFF000000;
        }
        return new TintColors(colors);
    }

    private static Model bakeModel(IBakedModel model, TransformType displayContext, Material material, TintColors tints, boolean foil) {
        ReferenceArraySet<TextureAtlasSprite> spriteCollector =
                SmartAnimatedTextureCompat.ENABLED ? new ReferenceArraySet<>() : null;
        Mesh mesh = bakeMesh(model, displayContext, tints, spriteCollector);
        if (mesh == null) return EmptyModel.INSTANCE;
        Model out = !foil ? new SingleMeshModel(mesh, material) : new SimpleModel(List.of(
                new Model.ConfiguredMesh(material, mesh),
                new Model.ConfiguredMesh(Materials.GLINT, mesh),
                new Model.ConfiguredMesh(Materials.GLINT_2, mesh)));
        // GLINT_2 is a CS-only second pass restoring vanilla 1.12.2's first renderEffect call
        // (rotation -50°, scroll vec2(p/24, 0)); without it foil items show only half of
        // vanilla's iridescence. See glint2.vert for rationale.
        if (spriteCollector != null && !spriteCollector.isEmpty()) {
            SmartAnimatedTextureCompat.register(out, spriteCollector.toArray(new TextureAtlasSprite[0]));
        }
        return out;
    }

    @Nullable
    private static Mesh bakeMesh(IBakedModel model, TransformType displayContext, TintColors tints, @Nullable ReferenceArraySet<TextureAtlasSprite> spriteCollector) {
        // Forge's IPerspectiveAwareModel (every "item/generated" 2D item) returns DEFAULT from
        // getItemCameraTransforms() and routes the real transform through handlePerspective —
        // reading from getItemCameraTransforms would collapse GROUND scale=0.5 to identity.
        // handlePerspective already wraps as T(-0.5) * tr * T(+0.5); vanilla then translates
        // by -0.5 before drawing quads in [0,1] corner space, leaving T(-0.5) * tr.
        var pair = model.handlePerspective(displayContext);
        IBakedModel actualModel = pair.getLeft();
        var hpMatrix = pair.getRight();

        Matrix4f poseMatrix = new Matrix4f();
        if (hpMatrix != null) {
            // vecmath uses m<row><col>; JOML uses m<col><row> column-major.
            poseMatrix.set(
                    hpMatrix.m00, hpMatrix.m10, hpMatrix.m20, hpMatrix.m30,
                    hpMatrix.m01, hpMatrix.m11, hpMatrix.m21, hpMatrix.m31,
                    hpMatrix.m02, hpMatrix.m12, hpMatrix.m22, hpMatrix.m32,
                    hpMatrix.m03, hpMatrix.m13, hpMatrix.m23, hpMatrix.m33);
        }
        poseMatrix.translate(-0.5F, -0.5F, -0.5F);

        Matrix3f normalMatrix = poseMatrix.normal(new Matrix3f());

        List<BakedQuad> allQuads = new ArrayList<>();
        for (EnumFacing dir : ModelUtil.DIRECTIONS) {
            allQuads.addAll(actualModel.getQuads(null, dir, 42L));
        }

        int vertexCount = allQuads.size() * 4;
        if (vertexCount == 0) return null;

        MemoryBlock memoryBlock = MemoryBlock.mallocTracked(vertexCount * FullVertexView.STRIDE);
        FullVertexView meshVertices = new FullVertexView();
        meshVertices.nativeMemoryOwner(memoryBlock);
        meshVertices.ptr(memoryBlock.ptr());
        meshVertices.vertexCount(vertexCount);

        Vector4f position = new Vector4f();
        Vector3f normal = new Vector3f();
        int[] tintColors = tints.colors();

        try (MemoryStack memoryStack = MemoryStack.stackPush()) {
            // BLOCK / Forge ITEM vertex stride is 28 bytes (7 ints); we read only the first 24.
            ByteBuffer byteBuffer = memoryStack.malloc(28);
            IntBuffer intBuffer = byteBuffer.asIntBuffer();

            int vertex = 0;
            for (BakedQuad quad : allQuads) {
                if (spriteCollector != null) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    if (sprite.hasAnimationMetadata()) {
                        spriteCollector.add(sprite);
                    }
                }
                int[] vertexData = quad.getVertexData();
                int stride = vertexData.length / 4;
                EnumFacing face = quad.getFace();
                Vec3i faceVec = face.getDirectionVec();
                normal.set(faceVec.getX(), faceVec.getY(), faceVec.getZ());
                normal.mul(normalMatrix);
                if (normal.lengthSquared() > 0F) normal.normalize();

                int tintIndex = quad.getTintIndex();
                float tR, tG, tB;
                if (tintIndex >= 0 && tintIndex < tintColors.length) {
                    int packed = tintColors[tintIndex];
                    tR = ((packed >> 16) & 0xFF) / 255F;
                    tG = ((packed >> 8) & 0xFF) / 255F;
                    tB = (packed & 0xFF) / 255F;
                } else {
                    tR = tG = tB = 1F;
                }

                for (int v = 0; v < 4; v++) {
                    intBuffer.clear();
                    intBuffer.put(vertexData, v * stride, Math.min(stride, 7));

                    position.set(byteBuffer.getFloat(0), byteBuffer.getFloat(4), byteBuffer.getFloat(8), 1.0f);
                    position.mul(poseMatrix);

                    meshVertices.x(vertex, position.x());
                    meshVertices.y(vertex, position.y());
                    meshVertices.z(vertex, position.z());
                    meshVertices.r(vertex, tR);
                    meshVertices.g(vertex, tG);
                    meshVertices.b(vertex, tB);
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

        return new SimpleQuadMesh(meshVertices, "ItemModel[" + displayContext + "]");
    }

    private record BakedModelKey(IBakedModel model, TransformType transformType, Material material, TintColors tints, boolean foil) {
    }

    // Custom equals/hashCode — record defaults use identity hash for int[].
    private record TintColors(int[] colors) {
        @Override
        public int hashCode() {
            return Arrays.hashCode(colors);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TintColors t && Arrays.equals(colors, t.colors);
        }
    }
}
