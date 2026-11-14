package dev.engine_room.flywheel.api.visual;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.function.Consumer;

/**
 * The desired distant representation of a {@link FarVisual}. The renderer consumes this declaration before the
 * visual is deleted and owns its resulting geometry, instance data, and embedding snapshots.
 *
 * <p>Independent coordinate spaces let a composite visual include its own geometry and embedded child visuals.
 * Each space resolves its context's complete current transform hierarchy once; contexts are not composed again
 * merely because one space names an embedding parent of another.
 */
public record FarVisualState(List<Space> spaces) {
    public FarVisualState {
        spaces = List.copyOf(spaces);
    }

    /**
     * Nodes expressed in the same coordinates as instances created through {@code context}. An embedding's supplied
     * pose and normal transforms, including its parents, apply to every node in this space.
     */
    public record Space(VisualizationContext context, List<Node> children) {
        public Space {
            children = List.copyOf(children);
        }
    }

    public sealed interface Node permits Geometry, InstanceState, Group {
    }

    /**
     * Static model geometry that may be baked into distant terrain. Its shape does not depend on live instances.
     */
    public record Geometry(Model model, Appearance appearance) implements Node {
    }

    /**
     * Desired state for an instance whose own GPU implementation remains active at a distance.
     *
     * <p>The renderer invokes {@code initializer} synchronously on a newly created detached instance before deleting
     * the visual. It must assign the desired state, rather than copy a live instance or perform visual lifecycle
     * operations. The renderer owns serialization and does not retain the initializer or its captured references.
     * Group and embedding transforms apply outside the instance's own shader animation.
     */
    public record InstanceState<I extends Instance>(InstanceType<I> type, Model model, int bias,
                                                    Consumer<? super I> initializer) implements Node {
    }

    /**
     * A shared local transform for static geometry, GPU instances, and nested groups. The normal transform is
     * supplied independently, just as it is for an embedding.
     */
    public record Group(Matrix4fc pose, Matrix3fc normal, List<Node> children) implements Node {
        public Group {
            pose = new Matrix4f(pose);
            normal = new Matrix3f(normal);
            children = List.copyOf(children);
        }
    }

    /**
     * Per-placement appearance for static geometry. Color uses ARGB packing; light and overlay use vanilla packing.
     */
    public record Appearance(int colorArgb, int light, int overlay, int bias) {
        public static Appearance lit(int light) {
            return new Appearance(0xFFFFFFFF, light, OverlayTexture.NO_OVERLAY, 0);
        }
    }
}
