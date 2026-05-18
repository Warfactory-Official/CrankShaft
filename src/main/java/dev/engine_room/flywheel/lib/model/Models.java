package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.model.baked.SinglePosVirtualBlockGetter;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.function.BiConsumer;

/**
 * A collection of methods for creating models from various sources.
 * <br>
 * All Models returned from this class are cached, so calling the same
 * method with the same parameters will return the same object.
 */
public final class Models {
    private static final RendererReloadCache<IBlockState, Model> BLOCK_STATE = new RendererReloadCache<>(it ->
            new BlockModelBuilder(SinglePosVirtualBlockGetter.createFullDark().blockState(it),
                    Collections.singletonList(BlockPos.ORIGIN)).build());
    private static final RendererReloadCache<PartialModel, Model> PARTIAL = new RendererReloadCache<>(it ->
            new BakedModelBuilder(it.get()).build());
    private static final RendererReloadCache<TransformedPartial<?>, Model> TRANSFORMED_PARTIAL = new RendererReloadCache<>(TransformedPartial::create);

    private Models() {
    }

    /**
     * Get a usable model for a given block state.
     *
     * @param state The block state you wish to render.
     * @return A model corresponding to how the given block state would appear in the level.
     */
    public static Model block(IBlockState state) {
        return BLOCK_STATE.get(state);
    }

    /**
     * Get a usable model for a given partial model.
     * @param partial The partial model you wish to render.
     * @return A model built from the baked model the partial model represents.
     */
    public static Model partial(PartialModel partial) {
        return PARTIAL.get(partial);
    }

    /**
     * Get a usable model for a given partial model, transformed in some way.
     * <br>
     * In general, you should try to avoid captures in the transformer function,
     * i.e. prefer static method references over lambdas, so the cache key compares by identity meaningfully.
     *
     * @param partial     The partial model you wish to render.
     * @param key         A key that will be used to cache the transformed model.
     * @param transformer A function that will transform the model in some way.
     * @param <T>         The type of the key.
     * @return A model built from the baked model the partial model represents, transformed by the given function.
     */
    public static <T> Model partial(PartialModel partial, T key, BiConsumer<T, Matrix4f> transformer) {
        return TRANSFORMED_PARTIAL.get(new TransformedPartial<>(partial, key, transformer));
    }

    /**
     * Get a usable model for a given partial model, transformed to face a given direction.
     * <br>
     * {@link EnumFacing#NORTH} is considered the default direction and the corresponding transform will be a no-op.
     *
     * @param partial The partial model you wish to render.
     * @param dir The direction you wish the model to be rotated to.
     * @return A model built from the baked model the partial model represents, transformed to face the given direction.
     */
    public static Model partial(PartialModel partial, EnumFacing dir) {
        return partial(partial, dir, Models::rotateAboutCenterToFace);
    }

    private static void rotateAboutCenterToFace(EnumFacing facing, Matrix4f mat) {
        mat.translate(0.5f, 0.5f, 0.5f);
        applyFacing(facing.getOpposite(), mat);
        mat.translate(-0.5f, -0.5f, -0.5f);
    }

    // Mirror lib/transform/Rotate.rotateToFace's angle table.
    private static void applyFacing(EnumFacing facing, Matrix4f mat) {
        switch (facing) {
            case DOWN -> mat.rotateX((float) Math.toRadians(-90));
            case UP -> mat.rotateX((float) Math.toRadians(90));
            case NORTH -> {
            }
            case SOUTH -> mat.rotateY((float) Math.toRadians(180));
            case WEST -> mat.rotateY((float) Math.toRadians(90));
            case EAST -> mat.rotateY((float) Math.toRadians(270));
        }
    }

    private record TransformedPartial<T>(PartialModel partial, T key, BiConsumer<T, Matrix4f> transformer) {
        private Model create() {
            Matrix4f pose = new Matrix4f();
            transformer.accept(key, pose);
            return new BakedModelBuilder(partial.get()).pose(pose).build();
        }
    }
}
