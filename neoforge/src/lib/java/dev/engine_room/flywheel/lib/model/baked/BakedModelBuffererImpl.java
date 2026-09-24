package dev.engine_room.flywheel.lib.model.baked;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.lib.util.ItemFoil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

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
                result.put(entry.getKey(), entry.getValue().build(state));
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
                result.put(entry.getKey(), entry.getValue().build(null));
            }
        }
        return result;
    }

    @Override
    @Nullable
    public List<DisplayMesh> bufferDisplayBlock(BlockState state, BlockDisplayContext context, boolean zOffset) {
        DisplayBlockCapture capture = new DisplayBlockCapture();
        if (!capture.run(state, context, zOffset)) {
            return null;
        }
        List<DisplayMesh> meshes = new ArrayList<>();
        for (DisplayBlockCapture.Captured submit : capture.captured()) {
            ItemMeshEmitter emitter = new ItemMeshEmitter();
            emitParts(emitter, submit.parts(), submit.pose(), submit.tints());
            meshes.add(new DisplayMesh(submit.renderType(), emitter.build(state)));
        }
        return meshes;
    }

    private static void emitParts(ItemMeshEmitter emitter, List<BlockStateModelPart> parts, PoseStack.Pose pose,
                                  int[] tints) {
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                emitQuads(emitter, part.getQuads(direction), pose, tints);
            }
            emitQuads(emitter, part.getQuads(null), pose, tints);
        }
    }

    // BlockModelFeatureRenderer.putQuad with the submit's -1 base tint.
    private static void emitQuads(ItemMeshEmitter emitter, List<BakedQuad> quads, PoseStack.Pose pose, int[] tints) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            int tint = tintIndex != -1 && tintIndex < tints.length ? tints[tintIndex] : -1;
            emitter.accept(pose.pose(), pose.normal(), quad, tint);
        }
    }

    @Override
    @Nullable
    public ItemMeshes bufferItem(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner, int seed) {
        return bufferItem(stack, displayContext, owner, seed, false);
    }

    @Override
    @Nullable
    public ItemMeshes bufferItemInVisualFrame(ItemStack stack, ItemDisplayContext displayContext,
                                              @Nullable ItemOwner owner, int seed) {
        return bufferItem(stack, displayContext, owner, seed, true);
    }

    @Nullable
    private static ItemMeshes bufferItem(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner,
                                         int seed, boolean visualFrame) {
        Minecraft minecraft = Minecraft.getInstance();
        // Fresh scratch state per bake (cached upstream by model identity); Tracking* captures the model-identity elements for the cache key.
        TrackingItemStackRenderState renderState = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(renderState, stack, displayContext, minecraft.level, owner, seed);
        if (renderState.activeLayerCount == 0) {
            return null;
        }

        EnumMap<ItemMeshKey, ItemMeshEmitter> emitters = new EnumMap<>(ItemMeshKey.class);
        boolean foil = ItemFoil.of(stack);
        // The combined display transform (ItemTransform + localTransform, incl. the -0.5 recenter) per layer.
        PoseStack.Pose pose = new PoseStack.Pose();
        @Nullable Matrix4f first = null;

        for (int i = 0; i < renderState.activeLayerCount; i++) {
            ItemStackRenderState.LayerRenderState layer = renderState.layers[i];
            if (layer.specialRenderer != null) {
                return null; // special / block-entity-renderer item (skull, banner, shield, ...) -> vanilla renders it
            }
            List<BakedQuad> quads = layer.prepareQuadList();
            if (quads.isEmpty()) {
                continue;
            }
            int[] tints = layer.tintLayers().toIntArray();

            pose.setIdentity();
            layer.applyTransform(pose);
            if (visualFrame) {
                if (first == null) {
                    first = new Matrix4f(pose.pose());
                } else if (!first.equals(pose.pose())) {
                    throw new IllegalStateException("layers of " + stack + " differ in display transform");
                }
                pose.setIdentity();
            }
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
                meshes.put(entry.getKey(), entry.getValue().build(stack.getItem(), stack.get(DataComponents.ITEM_MODEL)));
            }
        }
        if (meshes.isEmpty()) {
            return null;
        }
        var boundingBox = renderState.getModelBoundingBox();
        return new ItemMeshes(meshes, foil, (float) boundingBox.minY, (float) boundingBox.getZsize(),
                isStackDetermined(renderState), isOwnerDependent(renderState), renderState.getModelIdentity());
    }

    // The tracked identity IS the resolved path; it stays time-stable iff every on-path decision node selects by a stack-determined property.
    private static boolean isStackDetermined(TrackingItemStackRenderState renderState) {
        for (Object element : (List<?>) renderState.getModelIdentity()) {
            Object property = property(element);
            if (property != null && !ItemModelProperties.STACK_DETERMINED.contains(property.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOwnerDependent(TrackingItemStackRenderState renderState) {
        for (Object element : (List<?>) renderState.getModelIdentity()) {
            Object property = property(element);
            if (property != null && ItemModelProperties.OWNER_STATE.contains(property.getClass())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Object property(Object element) {
        return switch (element) {
            case ConditionalItemModel model -> model.property;
            case SelectItemModel<?> model -> model.property;
            case RangeSelectItemModel model -> model.property;
            default -> null;
        };
    }
}
