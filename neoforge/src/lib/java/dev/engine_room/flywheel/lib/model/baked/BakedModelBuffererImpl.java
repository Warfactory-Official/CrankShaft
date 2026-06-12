package dev.engine_room.flywheel.lib.model.baked;

import java.util.EnumMap;
import java.util.List;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBufferer;
import dev.engine_room.flywheel.lib.model.baked.SinglePosVirtualBlockGetter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.ConditionalItemModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.RangeSelectItemModel;
import net.minecraft.client.renderer.item.SelectItemModel;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class BakedModelBuffererImpl implements BakedModelBufferer {
    @Override
    public EnumMap<ChunkSectionLayer, BakedMesh> bufferBlock(BlockState state, int cullMask, long seed) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModel model = minecraft.getModelManager()
                .getBlockStateModelSet()
                .get(state);
        BlockColors blockColors = minecraft.getBlockColors();

        EnumMap<ChunkSectionLayer, MeshEmitter> emitters = new EnumMap<>(ChunkSectionLayer.class);
        BlockQuadOutput output = (x, y, z, quad, instance) -> {
            Direction direction = quad.direction();
            if (direction != null && (cullMask & (1 << direction.ordinal())) != 0) {
                return;
            }
            emitters.computeIfAbsent(quad.materialInfo()
                            .layer(), $ -> new MeshEmitter())
                    .accept(x, y, z, quad, instance);
        };

        // ao=false: flat lighting (the virtual level has no light neighborhood); cull=false: capture every face (occlusion applied via cullMask).
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, false, blockColors);
        SinglePosVirtualBlockGetter level = new SinglePosVirtualBlockGetter(state);

        BlockModelLighter.enableCaching();
        try {
            // Cancel the state's coordinate offset (flowers, dripstone): tesselateBlock bakes getOffset(pos)
            // into the mesh, but consumers draw in LOCAL 0..1 space where vanilla's paths apply no offset.
            var stateOffset = state.getOffset(BlockPos.ZERO);
            renderer.tesselateBlock(output, (float) -stateOffset.x, (float) -stateOffset.y, (float) -stateOffset.z,
                    level, BlockPos.ZERO, state, model, seed);
        } finally {
            BlockModelLighter.clearCache();
        }

        EnumMap<ChunkSectionLayer, BakedMesh> result = new EnumMap<>(ChunkSectionLayer.class);
        for (var entry : emitters.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), entry.getValue().build());
            }
        }
        return result;
    }

    @Override
    public EnumMap<ChunkSectionLayer, BakedMesh> bufferModel(BlockStateModel model, @Nullable PoseStack poseStack) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockColors blockColors = minecraft.getBlockColors();
        // The standalone model's geometry is state-independent; AIR gives it a neighbour-free (uncullable) context.
        BlockState state = Blocks.AIR.defaultBlockState();

        Matrix4fc pose = poseStack != null ? poseStack.last().pose() : null;
        Matrix3fc normalMatrix = poseStack != null ? poseStack.last().normal() : null;

        EnumMap<ChunkSectionLayer, MeshEmitter> emitters = new EnumMap<>(ChunkSectionLayer.class);
        BlockQuadOutput output = (x, y, z, quad, instance) ->
                emitters.computeIfAbsent(quad.materialInfo().layer(), $ -> new MeshEmitter(pose, normalMatrix))
                        .accept(x, y, z, quad, instance);

        ModelBlockRenderer renderer = new ModelBlockRenderer(false, false, blockColors);
        SinglePosVirtualBlockGetter level = new SinglePosVirtualBlockGetter(state);

        BlockModelLighter.enableCaching();
        try {
            renderer.tesselateBlock(output, 0.0f, 0.0f, 0.0f, level, BlockPos.ZERO, state, model, state.getSeed(BlockPos.ZERO));
        } finally {
            BlockModelLighter.clearCache();
        }

        EnumMap<ChunkSectionLayer, BakedMesh> result = new EnumMap<>(ChunkSectionLayer.class);
        for (var entry : emitters.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), entry.getValue().build());
            }
        }
        return result;
    }

    @Override
    @Nullable
    public ItemMeshes bufferItem(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner, int seed) {
        Minecraft minecraft = Minecraft.getInstance();
        // Fresh scratch state per bake (cached upstream by model identity); Tracking* captures the model-identity elements for the cache key.
        TrackingItemStackRenderState renderState = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(renderState, stack, displayContext, minecraft.level, owner, seed);
        if (renderState.activeLayerCount == 0) {
            return null;
        }

        EnumMap<ItemMeshKey, ItemMeshEmitter> emitters = new EnumMap<>(ItemMeshKey.class);
        boolean foil = false;
        // The combined display transform (ItemTransform + localTransform, incl. the -0.5 recenter) per layer.
        PoseStack.Pose pose = new PoseStack.Pose();

        for (int i = 0; i < renderState.activeLayerCount; i++) {
            ItemStackRenderState.LayerRenderState layer = renderState.layers[i];
            if (layer.specialRenderer != null) {
                return null; // special / block-entity-renderer item (skull, banner, shield, ...) -> vanilla renders it
            }
            if (layer.foilType != ItemStackRenderState.FoilType.NONE) {
                foil = true;
            }
            List<BakedQuad> quads = layer.prepareQuadList();
            if (quads.isEmpty()) {
                continue;
            }
            int[] tints = layer.tintLayers().toIntArray();

            pose.setIdentity();
            layer.applyTransform(pose);
            Matrix4fc poseMatrix = pose.pose();
            Matrix3fc normalMatrix = pose.normal();

            for (BakedQuad quad : quads) {
                int tintIndex = quad.materialInfo().tintIndex();
                int tint = tintIndex >= 0 && tintIndex < tints.length ? tints[tintIndex] : -1;
                // Mirrors MaterialInfo.of's ITEM branch: item rendering is cutout MINIMUM (Sheets.cutoutItemSheet),
                // not the quad's terrain-context SOLID layer; the atlas axis mirrors atlasLocation() -- 26.2 stitches
                // item sprites onto their own atlas, so UVs are only meaningful against the atlas its sprite lives on.
                boolean blocksAtlas = quad.materialInfo().sprite().atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS);
                emitters.computeIfAbsent(ItemMeshKey.of(quad.materialInfo().layer().translucent(), blocksAtlas), $ -> new ItemMeshEmitter())
                        .accept(poseMatrix, normalMatrix, quad, tint);
            }
        }

        EnumMap<ItemMeshKey, BakedMesh> meshes = new EnumMap<>(ItemMeshKey.class);
        for (var entry : emitters.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                meshes.put(entry.getKey(), entry.getValue().build());
            }
        }
        if (meshes.isEmpty()) {
            return null;
        }
        var boundingBox = renderState.getModelBoundingBox();
        return new ItemMeshes(meshes, foil, (float) boundingBox.minY, (float) boundingBox.getZsize(),
                isStackDetermined(renderState), renderState.getModelIdentity());
    }

    // The tracked identity IS the resolved path; it stays time-stable iff every on-path decision node selects by a stack-determined property.
    private static boolean isStackDetermined(TrackingItemStackRenderState renderState) {
        for (Object element : (List<?>) renderState.getModelIdentity()) {
            Object property = switch (element) {
                case ConditionalItemModel model -> model.property;
                case SelectItemModel<?> model -> model.property;
                case RangeSelectItemModel model -> model.property;
                default -> null;
            };
            if (property != null && !ItemModelProperties.STACK_DETERMINED.contains(property.getClass())) {
                return false;
            }
        }
        return true;
    }
}
