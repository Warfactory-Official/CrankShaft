package dev.engine_room.flywheel.lib.model.part;

import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.baked.ModelBaseConverter;
import net.minecraft.client.model.ModelRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

public final class InstanceTree {
    private static final float DEFAULT_SCALE = 1.0F;

    private final ModelTree source;
    @Nullable
    private final TransformedInstance instance;
    private final InstanceTree[] children;

    private final Matrix4f poseMatrix = new Matrix4f();

    private float x;
    private float y;
    private float z;
    private float xRot;
    private float yRot;
    private float zRot;
    private float xScale = DEFAULT_SCALE;
    private float yScale = DEFAULT_SCALE;
    private float zScale = DEFAULT_SCALE;
    private boolean visible = true;
    private boolean skipDraw = false;

    private boolean changed;

    private InstanceTree(ModelTree source, @Nullable TransformedInstance instance, InstanceTree[] children) {
        this.source = source;
        this.instance = instance;
        this.children = children;
        resetPose();
    }

    public static InstanceTree create(InstancerProvider provider, ModelTree modelTree) {
        InstanceTree[] children = new InstanceTree[modelTree.childCount()];
        for (int i = 0; i < modelTree.childCount(); i++) {
            children[i] = create(provider, modelTree.child(i));
        }

        Model model = modelTree.model();
        TransformedInstance instance = model != null
                ? provider.instancer(InstanceTypes.TRANSFORMED, model).createInstance()
                : null;

        return new InstanceTree(modelTree, instance, children);
    }

    @Nullable
    public TransformedInstance instance() {
        return instance;
    }

    public PartPose initialPose() {
        return source.initialPose();
    }

    public int childCount() {
        return children.length;
    }

    public InstanceTree child(int index) {
        return children[index];
    }

    public void traverse(Consumer<? super TransformedInstance> consumer) {
        if (instance != null) {
            consumer.accept(instance);
        }
        for (InstanceTree child : children) {
            child.traverse(consumer);
        }
    }

    public void translateAndRotate(Matrix4f pose) {
        pose.translate(x, y, z);

        if (xRot != 0F || yRot != 0F || zRot != 0F) {
            pose.rotateZYX(zRot, yRot, xRot);
        }

        if (xScale != DEFAULT_SCALE || yScale != DEFAULT_SCALE || zScale != DEFAULT_SCALE) {
            pose.scale(xScale, yScale, zScale);
        }
    }

    /**
     * Update the instances in this tree, assuming initialPose may change between invocations.
     * Preferred for entity visuals.
     */
    public void updateInstances(Matrix4fc initialPose) {
        propagateAnimation(initialPose, true);
    }

    /**
     * Update the instances in this tree, assuming initialPose is stable between invocations.
     * Preferred for block entity visuals.
     */
    public void updateInstancesStatic(Matrix4fc initialPose) {
        propagateAnimation(initialPose, false);
    }

    public void propagateAnimation(Matrix4fc initialPose, boolean force) {
        if (!visible) {
            return;
        }

        if (changed || force) {
            poseMatrix.set(initialPose);
            translateAndRotate(poseMatrix);
            force = true;

            if (instance != null && !skipDraw) {
                instance.setTransform(poseMatrix);
                instance.setChanged();
            }
        }

        for (InstanceTree child : children) {
            child.propagateAnimation(poseMatrix, force);
        }

        changed = false;
    }

    /** Recursively set visibility on this node and all descendants. */
    public void visible(boolean visible) {
        this.visible = visible;
        updateVisible();
        for (InstanceTree child : children) {
            child.visible(visible);
        }
    }

    /** Skip drawing only this node; children still render. */
    public void skipDraw(boolean skipDraw) {
        this.skipDraw = skipDraw;
        updateVisible();
    }

    private void updateVisible() {
        if (instance != null) {
            instance.setVisible(visible && !skipDraw);
        }
    }

    public boolean visible() {
        return visible;
    }

    public boolean skipDraw() {
        return skipDraw;
    }

    public float xPos() { return x; }
    public float yPos() { return y; }
    public float zPos() { return z; }
    public float xRot() { return xRot; }
    public float yRot() { return yRot; }
    public float zRot() { return zRot; }
    public float xScale() { return xScale; }
    public float yScale() { return yScale; }
    public float zScale() { return zScale; }

    public void xPos(float x) { this.x = x; changed = true; }
    public void yPos(float y) { this.y = y; changed = true; }
    public void zPos(float z) { this.z = z; changed = true; }

    public void pos(float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
        changed = true;
    }

    public void xRot(float xRot) { this.xRot = xRot; changed = true; }
    public void yRot(float yRot) { this.yRot = yRot; changed = true; }
    public void zRot(float zRot) { this.zRot = zRot; changed = true; }

    public void rotation(float xRot, float yRot, float zRot) {
        this.xRot = xRot; this.yRot = yRot; this.zRot = zRot;
        changed = true;
    }

    public void xScale(float xScale) { this.xScale = xScale; changed = true; }
    public void yScale(float yScale) { this.yScale = yScale; changed = true; }
    public void zScale(float zScale) { this.zScale = zScale; changed = true; }

    public void scale(float xScale, float yScale, float zScale) {
        this.xScale = xScale; this.yScale = yScale; this.zScale = zScale;
        changed = true;
    }

    public void offsetPos(float dx, float dy, float dz) {
        x += dx; y += dy; z += dz;
        changed = true;
    }

    public void offsetRotation(float dxRot, float dyRot, float dzRot) {
        xRot += dxRot; yRot += dyRot; zRot += dzRot;
        changed = true;
    }

    public void offsetScale(float dxScale, float dyScale, float dzScale) {
        xScale += dxScale; yScale += dyScale; zScale += dzScale;
        changed = true;
    }

    public PartPose storePose() {
        return PartPose.offsetAndRotation(x, y, z, xRot, yRot, zRot);
    }

    public void loadPose(PartPose pose) {
        x = pose.x();
        y = pose.y();
        z = pose.z();
        xRot = pose.xRot();
        yRot = pose.yRot();
        zRot = pose.zRot();
        xScale = DEFAULT_SCALE;
        yScale = DEFAULT_SCALE;
        zScale = DEFAULT_SCALE;
        changed = true;
    }

    public void resetPose() {
        loadPose(source.initialPose());
    }

    public void copyTransform(InstanceTree tree) {
        x = tree.x; y = tree.y; z = tree.z;
        xRot = tree.xRot; yRot = tree.yRot; zRot = tree.zRot;
        xScale = tree.xScale; yScale = tree.yScale; zScale = tree.zScale;
        changed = true;
    }

    /** Copy pose from a vanilla ModelRenderer. Position fields are converted from model units
     *  to world units via ModelBaseConverter.DEFAULT_SCALE. */
    public void copyTransform(ModelRenderer r) {
        x = r.rotationPointX * ModelBaseConverter.DEFAULT_SCALE;
        y = r.rotationPointY * ModelBaseConverter.DEFAULT_SCALE;
        z = r.rotationPointZ * ModelBaseConverter.DEFAULT_SCALE;
        xRot = r.rotateAngleX;
        yRot = r.rotateAngleY;
        zRot = r.rotateAngleZ;
        changed = true;
    }

    public void delete() {
        if (instance != null) {
            instance.delete();
        }
        for (InstanceTree child : children) {
            child.delete();
        }
    }
}
