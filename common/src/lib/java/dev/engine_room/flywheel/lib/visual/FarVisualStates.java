package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.visual.FarVisualState;
import dev.engine_room.flywheel.api.visualization.FarVisualModel;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.part.ModelTree;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds static distant declarations from authored model-part poses instead of a live instance tree.
 */
public final class FarVisualStates {
    private FarVisualStates() {
    }

    public static FarVisualState modelTree(VisualizationContext context, ModelTree tree, Matrix4fc pose,
                                           FarVisualState.Appearance appearance) {
        return modelTree(context, tree, pose, tree.initialPose(), appearance);
    }

    /**
     * Uses the supplied root pose and the initial poses of every descendant.
     */
    public static FarVisualState modelTree(VisualizationContext context, ModelTree tree, Matrix4fc pose,
                                           PartPose rootPose, FarVisualState.Appearance appearance) {
        var model = modelTree(tree, pose, rootPose, appearance);
        return new FarVisualState(List.of(new FarVisualState.Space(context, model.children())));
    }

    public static FarVisualModel modelTree(ModelTree tree, Matrix4fc pose, FarVisualState.Appearance appearance) {
        return modelTree(tree, pose, tree.initialPose(), appearance);
    }

    public static FarVisualModel modelTree(ModelTree tree, Matrix4fc pose, PartPose rootPose,
                                           FarVisualState.Appearance appearance) {
        var root = new FarVisualState.Group(pose, new Matrix3f(pose).invert().transpose(),
                List.of(modelTree(tree, rootPose, appearance)));
        return new FarVisualModel(List.of(root));
    }

    private static FarVisualState.Node modelTree(ModelTree tree, PartPose pose,
                                                 FarVisualState.Appearance appearance) {
        var children = new ArrayList<FarVisualState.Node>(tree.childCount() + 1);
        if (tree.model() != null) {
            children.add(new FarVisualState.Geometry(tree.model(), appearance));
        }
        for (int i = 0; i < tree.childCount(); i++) {
            var child = tree.child(i);
            children.add(modelTree(child, child.initialPose(), appearance));
        }

        var transform = new Matrix4f().translate(pose.x() / 16f, pose.y() / 16f, pose.z() / 16f)
                                      .rotateZYX(pose.zRot(), pose.yRot(), pose.xRot())
                                      .scale(pose.xScale(), pose.yScale(), pose.zScale());
        return new FarVisualState.Group(transform, new Matrix3f(transform).invert().transpose(), children);
    }
}
