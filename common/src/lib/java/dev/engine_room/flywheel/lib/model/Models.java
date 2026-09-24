package dev.engine_room.flywheel.lib.model;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.model.Model.ConfiguredMesh;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBufferer;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Public entry point for baking vanilla content into flywheel {@link Model}s; results are cached.
 */
public final class Models {
    private static final Map<BlockState, Model> BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<DisplayKey, Optional<Model>> DISPLAY_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<SeededBlockKey, Model> SEEDED_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<CulledKey, Model> CULLED_CACHE = new ConcurrentHashMap<>();
    private static final Map<PartialModel, Model> PARTIAL_CACHE = new ConcurrentHashMap<>();
    private static final Map<TransformedPartial<?>, Model> TRANSFORMED_PARTIAL_CACHE = new ConcurrentHashMap<>();

    private Models() {
    }

    public static Model block(BlockState state) {
        return BLOCK_CACHE.computeIfAbsent(state, BlockModelBuilder::build);
    }

    /**
     * Bake a block as vanilla draws it in an entity context (minecart contents, displays, item frames, golem /
     * mooshroom / enderman blocks): {@code BlockModelResolver} into a {@code BlockModelRenderState}, the renderer's own
     * {@code context}. Unshaded quads, lit by the item-sheet material; light with {@link #displayBlockLight}.
     * {@code null} when a special renderer draws the block: leave it to vanilla.
     */
    @Nullable
    public static Model displayBlock(BlockState state, BlockDisplayContext context) {
        return displayBlock(state, context, false);
    }

    /**
     * {@code zOffset}: {@code submitWithZOffset}, the item frame's frame.
     */
    @Nullable
    public static Model displayBlock(BlockState state, BlockDisplayContext context, boolean zOffset) {
        return DISPLAY_BLOCK_CACHE.computeIfAbsent(new DisplayKey(state, context, zOffset), Models::bakeDisplayBlock)
                                  .orElse(null);
    }

    /**
     * The block's own light for {@link #displayBlock}; the draw uses {@code LightCoordsUtil.max(entityLight, this)}.
     */
    public static int displayBlockLight(BlockState state) {
        return state.emissiveRendering() ? LightCoordsUtil.FULL_BRIGHT : LightCoordsUtil.pack(state.getLightEmission(), 0);
    }

    private static Optional<Model> bakeDisplayBlock(DisplayKey key) {
        List<BakedModelBufferer.DisplayMesh> buffered = BakedModelBufferer.INSTANCE.bufferDisplayBlock(key.state(),
                key.context(), key.zOffset());
        if (buffered == null) {
            return Optional.empty();
        }
        List<ConfiguredMesh> meshes = new ArrayList<>(buffered.size());
        for (BakedModelBufferer.DisplayMesh submit : buffered) {
            if (submit.mesh().vertexCount() == 0) {
                continue;
            }
            RenderType type = submit.renderType();
            Material material = type.hasBlending() ? Materials.TRANSLUCENT_BLOCK_ITEM
                    : type == Sheets.cutoutBlockItemSheet() ? Materials.CUTOUT_BLOCK_ITEM : Materials.SOLID_BLOCK_ITEM;
            meshes.add(new ConfiguredMesh(material, submit.mesh()));
        }
        return Optional.of(meshes.isEmpty() ? EmptyModel.INSTANCE : new SimpleModel(meshes));
    }

    /**
     * Bake a block state's model resolved with {@code seed} (vanilla's moving-block variant seed).
     */
    public static Model block(BlockState state, long seed) {
        var model = Minecraft.getInstance()
                             .getModelManager()
                             .getBlockStateModelSet()
                             .get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        // Mirrors ModelBlockRenderer.tesselateBlock's variant resolution: a SingleThreadedRandomSource seeded
        // with the block seed. The bake itself re-resolves from the raw seed; this list is only the cache key.
        model.collectParts(RandomSource.createThreadLocalInstance(seed), parts);
        return SEEDED_BLOCK_CACHE.computeIfAbsent(new SeededBlockKey(state, parts),
                k -> BlockModelBuilder.build(k.state(), 0, seed));
    }

    /**
     * Bake a block state's model with the faces in {@code cullMask} removed (bit per {@link net.minecraft.core.Direction#ordinal()}).
     */
    public static Model block(BlockState state, int cullMask) {
        if (cullMask == 0) {
            return block(state);
        }
        return CULLED_CACHE.computeIfAbsent(new CulledKey(state, cullMask),
                k -> BlockModelBuilder.build(k.state(), k.cullMask()));
    }

    /**
     * Bake (or fetch the cached bake of) a standalone {@link PartialModel}.
     */
    public static Model partial(PartialModel partial) {
        BlockStateModel baked = partial.get();
        if (baked == null) {
            return EmptyModel.INSTANCE;
        }
        return PARTIAL_CACHE.computeIfAbsent(partial, p -> new BakedModelBuilder(p.get()).build());
    }

    /**
     * Bake a {@link PartialModel} transformed by {@code transformer}, cached on {@code key}.
     */
    public static <T> Model partial(PartialModel partial, T key, BiConsumer<T, PoseStack> transformer) {
        if (partial.get() == null) {
            return EmptyModel.INSTANCE;
        }
        return TRANSFORMED_PARTIAL_CACHE.computeIfAbsent(new TransformedPartial<>(partial, key, transformer),
                TransformedPartial::create);
    }

    public static Model partial(PartialModel partial, Direction dir) {
        return partial(partial, dir, Models::rotateAboutCenterToFace);
    }

    public static void invalidate() {
        BLOCK_CACHE.clear();
        DISPLAY_BLOCK_CACHE.clear();
        SEEDED_BLOCK_CACHE.clear();
        CULLED_CACHE.clear();
        PARTIAL_CACHE.clear();
        TRANSFORMED_PARTIAL_CACHE.clear();
    }

    private static void rotateAboutCenterToFace(Direction facing, PoseStack stack) {
        TransformStack.of(stack)
                      .center()
                      .rotateToFace(facing.getOpposite())
                      .uncenter();
    }

    private record CulledKey(BlockState state, int cullMask) {
    }

    private record DisplayKey(BlockState state, BlockDisplayContext context, boolean zOffset) {
    }

    // The variant parts are baked singletons, so the list's element-identity equality keys the resolved outcome.
    private record SeededBlockKey(BlockState state, List<BlockStateModelPart> parts) {
    }

    private record TransformedPartial<T>(PartialModel partial, T key, BiConsumer<T, PoseStack> transformer) {
        private Model create() {
            var stack = new PoseStack();
            transformer.accept(key, stack);
            return new BakedModelBuilder(partial.get())
                    .poseStack(stack)
                    .build();
        }
    }
}
