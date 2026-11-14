package dev.engine_room.flywheel.impl.far;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.api.layout.VectorElementType;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.FarVisualState;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.FarVisualModel;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.far.FarBounds;
import dev.engine_room.flywheel.backend.engine.far.FarInstance;
import dev.engine_room.flywheel.backend.engine.far.FarRow;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Resolves visual declarations into backend data synchronously on the visual's owning thread.
 */
final class VisualStateCapture {
    private static final float CELL_SLACK = 1 / 16f;
    private static final Matrix4fc IDENTITY_POSE = new Matrix4f();
    private static final Matrix3fc IDENTITY_NORMAL = new Matrix3f();
    private static final Vector3fc ZERO = new Vector3f();
    private static final Map<Model, Boolean> OVERSIZED = Collections.synchronizedMap(new WeakHashMap<>());

    private VisualStateCapture() {
    }

    static void capture(FarVisualModel model, BlockState blockState, List<FarRow> rows) {
        var pose = new Matrix4f();
        var normal = new Matrix3f();
        for (var child : model.children()) capture(child, blockState, pose, normal, rows);
    }

    static void capture(FarVisualState state, BlockState blockState, Vec3i origin, List<FarRow> rows) {
        for (var space : state.spaces()) {
            var pose = new Matrix4f();
            var normal = new Matrix3f();
            coordinateFrame(space.context(), origin, pose, normal);
            for (var child : space.children()) capture(child, blockState, pose, normal, rows);
        }
    }

    private static void coordinateFrame(VisualizationContext context, Vec3i origin, Matrix4f pose,
                                        Matrix3f normal) {
        if (context instanceof EmbeddedEnvironment embedded) {
            embedded.captureTransforms(origin, pose, normal);
        } else {
            var contextOrigin = context.renderOrigin();
            pose.translation(contextOrigin.getX() - origin.getX(), contextOrigin.getY() - origin.getY(),
                    contextOrigin.getZ() - origin.getZ());
        }
    }

    private static void capture(FarVisualState.Node state, BlockState blockState, Matrix4f pose, Matrix3f normal,
                                List<FarRow> rows) {
        switch (state) {
            case FarVisualState.Group group -> {
                var childPose = new Matrix4f(pose).mul(group.pose());
                var childNormal = new Matrix3f(normal).mul(group.normal());
                for (var child : group.children()) capture(child, blockState, childPose, childNormal, rows);
            }
            case FarVisualState.Geometry geometry -> {
                var appearance = geometry.appearance();
                var instance = initialize(InstanceTypes.POSED, geometry.model(), appearance.bias(), blockState,
                        IDENTITY_POSE, IDENTITY_NORMAL, true, scratch -> {
                            scratch.setTransform(pose, normal);
                            scratch.colorArgb(appearance.colorArgb());
                            scratch.light(appearance.light());
                            scratch.overlay(appearance.overlay());
                        });
                rows.add(still(instance));
            }
            case FarVisualState.InstanceState<?> instance -> rows.add(still(instance(instance, blockState, pose,
                    normal)));
        }
    }

    private static <I extends Instance> FarInstance instance(FarVisualState.InstanceState<I> state,
                                                             BlockState blockState, Matrix4f pose, Matrix3f normal) {
        return initialize(state.type(), state.model(), state.bias(), blockState, new Matrix4f(pose),
                new Matrix3f(normal), false, state.initializer());
    }

    private static <I extends Instance> FarInstance initialize(InstanceType<I> type, Model model, int bias,
                                                               BlockState blockState, Matrix4fc pose, Matrix3fc normal,
                                                               boolean terrain, Consumer<? super I> initializer) {
        int size = type.layout().byteSize();
        long ptr = MemoryUtil.nmemCalloc(1, size);
        try {
            type.seed().accept(ptr);
            initializer.accept(type.create(new ScratchHandle(ptr)));
            byte[] data = new byte[size];
            MemoryUtil.memByteBuffer(ptr, size).get(data);
            return new FarInstance(type, model, bias, data, light(type.layout(), data), blockState, pose, normal,
                    terrain);
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    static FarRow still(FarInstance instance) {
        return new FarRow(instance, ZERO, ZERO, 0, 0, 0);
    }

    static boolean oversized(BlockState state) {
        return OVERSIZED.computeIfAbsent(Models.block(state), VisualStateCapture::reachesPastCell);
    }

    static FarRow block(BlockState state, Vec3i offset) {
        var instance = initialize(InstanceTypes.POSED, Models.block(state), 0, state,
                IDENTITY_POSE, IDENTITY_NORMAL, true, scratch -> scratch.setTransform(
                        new Matrix4f().translation(offset.getX(), offset.getY(), offset.getZ()), new Matrix3f()));
        return still(instance);
    }

    private static boolean reachesPastCell(Model model) {
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var configured : model.meshes()) FarBounds.growBounds(configured.mesh(), min, max);
        for (int axis = 0; axis < 3; axis++) {
            if (min[axis] < -CELL_SLACK || max[axis] > 1 + CELL_SLACK) return true;
        }
        return false;
    }

    private static int light(Layout layout, byte[] data) {
        var element = layout.asMap().get("light");
        if (element != null && element.type() instanceof VectorElementType vector && vector.size() == 2
                && vector.repr() == FloatRepr.UNSIGNED_SHORT) {
            return ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).getInt(element.byteOffset());
        }
        return 0x00F000F0;
    }

    private record ScratchHandle(long slabPtr) implements InstanceHandle {
        @Override
        public void setChanged() {
        }

        @Override
        public void setDeleted() {
        }

        @Override
        public boolean isVisible() {
            return true;
        }

        @Override
        public void setVisible(boolean visible) {
        }
    }
}
