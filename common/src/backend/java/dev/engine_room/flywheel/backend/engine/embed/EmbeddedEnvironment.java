package dev.engine_room.flywheel.backend.engine.embed;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import net.minecraft.core.Vec3i;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

public class EmbeddedEnvironment implements VisualEmbedding, Environment {
    private final EngineImpl engine;
    private final Vec3i renderOrigin;
    @Nullable
    private final EmbeddedEnvironment parent;
    private final InstancerProvider instancerProvider;
    private final int drawTag;

    private final Matrix4f pose = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private final Matrix4f poseComposed = new Matrix4f();
    private final Matrix3f normalComposed = new Matrix3f();
    private final Matrix4f posePrevious = new Matrix4f();

    public int matrixIndex = 0;

    private boolean flushed = false;
    private boolean deleted = false;

    public EmbeddedEnvironment(EngineImpl engine, Vec3i renderOrigin, @Nullable EmbeddedEnvironment parent,
                               int drawTag) {
        this.engine = engine;
        this.renderOrigin = renderOrigin;
        this.parent = parent;
        this.drawTag = drawTag;

        instancerProvider = new InstancerProvider() {
            @Override
            public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
                // Kinda cursed usage of anonymous classes here, but it does the job.
                return engine.instancer(EmbeddedEnvironment.this, type, model, bias);
            }
        };
    }

    public EmbeddedEnvironment(EngineImpl engine, Vec3i renderOrigin, int drawTag) {
        this(engine, renderOrigin, null, drawTag);
    }

    @Override
    public void transforms(Matrix4fc pose, Matrix3fc normal) {
        this.pose.set(pose);
        this.normal.set(normal);
    }

    @Override
    public InstancerProvider instancerProvider() {
        return instancerProvider;
    }

    @Override
    public Vec3i renderOrigin() {
        return renderOrigin;
    }

    @Override
    public VisualEmbedding createEmbedding(Vec3i renderOrigin) {
        var out = new EmbeddedEnvironment(engine, renderOrigin, this, drawTag);
        engine.environmentStorage()
              .track(out);
        return out;
    }

    @Override
    public ContextShader contextShader() {
        return ContextShader.EMBEDDED;
    }

    @Override
    public void setupDraw(GlProgram program) {
        program.setMat4(EmbeddingUniforms.MODEL_MATRIX, poseComposed);
        program.setMat3(EmbeddingUniforms.NORMAL_MATRIX, normalComposed);
    }

    @Override
    public int matrixIndex() {
        return matrixIndex;
    }

    @Override
    public int drawTag() {
        return drawTag;
    }

    public Matrix4fc pose() {
        return poseComposed;
    }

    public Matrix3fc normal() {
        return normalComposed;
    }

    /**
     * The composed pose of the previous {@link #flush}; the current one on the first.
     */
    public Matrix4fc posePrevious() {
        return posePrevious;
    }

    public void flush(long ptr) {
        posePrevious.set(poseComposed);
        poseComposed.identity();
        normalComposed.identity();

        composeMatrices(poseComposed, normalComposed);
        if (!flushed) {
            posePrevious.set(poseComposed);
            flushed = true;
        }

        ExtraMemoryOps.putMatrix4f(ptr, poseComposed);
        ExtraMemoryOps.putMatrix3fPadded(ptr + 16 * Float.BYTES, normalComposed);
    }

    /**
     * Copies current hierarchy transforms into a retained capture's origin, before the next GPU flush.
     */
    public void captureTransforms(Vec3i captureOrigin, Matrix4f pose, Matrix3f normal) {
        composeMatrices(pose, normal);
        var engineOrigin = engine.renderOrigin();
        pose.m30(pose.m30() + engineOrigin.getX() - captureOrigin.getX());
        pose.m31(pose.m31() + engineOrigin.getY() - captureOrigin.getY());
        pose.m32(pose.m32() + engineOrigin.getZ() - captureOrigin.getZ());
    }

    private void composeMatrices(Matrix4f pose, Matrix3f normal) {
        if (parent != null) {
            parent.composeMatrices(pose, normal);
            pose.mul(this.pose);
            normal.mul(this.normal);
        } else {
            pose.set(this.pose);
            normal.set(this.normal);
        }
    }

    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Called by visuals
     */
    @Override
    public void delete() {
        deleted = true;
    }
}
