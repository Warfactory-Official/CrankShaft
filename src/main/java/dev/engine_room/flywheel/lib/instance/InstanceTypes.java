package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

public final class InstanceTypes {
    private static final Matrix4f IDENTITY_M4 = new Matrix4f();
    private static final Matrix3f IDENTITY_M3 = new Matrix3f();
    private static final Quaternionf IDENTITY_QUAT = new Quaternionf();

    public static final InstanceType<TransformedInstance> TRANSFORMED = SimpleInstanceType.builder(TransformedInstance::new)
            .layout(LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("overlay", IntegerRepr.SHORT, 2)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .matrix("pose", FloatRepr.FLOAT, 4)
                    .build())
            .seed(ptr -> {
                MemoryUtil.memPutInt(ptr, 0xFFFFFFFF);
                ExtraMemoryOps.put2x16(ptr + 4, OverlayTexture.NO_OVERLAY);
                ExtraMemoryOps.putMatrix4f(ptr + 12, IDENTITY_M4);
            })
            .vertexShader(ResourceUtil.rl("instance/transformed.vert"))
            .cullShader(ResourceUtil.rl("instance/cull/transformed.glsl"))
            .build();

    public static final InstanceType<PosedInstance> POSED = SimpleInstanceType.builder(PosedInstance::new)
            .layout(LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("overlay", IntegerRepr.SHORT, 2)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .matrix("pose", FloatRepr.FLOAT, 4)
                    .matrix("normal", FloatRepr.FLOAT, 3)
                    .build())
            .seed(ptr -> {
                MemoryUtil.memPutInt(ptr, 0xFFFFFFFF);
                ExtraMemoryOps.put2x16(ptr + 4, OverlayTexture.NO_OVERLAY);
                ExtraMemoryOps.putMatrix4f(ptr + 12, IDENTITY_M4);
                ExtraMemoryOps.putMatrix3f(ptr + 76, IDENTITY_M3);
            })
            .vertexShader(ResourceUtil.rl("instance/posed.vert"))
            .cullShader(ResourceUtil.rl("instance/cull/posed.glsl"))
            .build();

    public static final InstanceType<OrientedInstance> ORIENTED = SimpleInstanceType.builder(OrientedInstance::new)
            .layout(LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("overlay", IntegerRepr.SHORT, 2)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .vector("position", FloatRepr.FLOAT, 3)
                    .vector("pivot", FloatRepr.FLOAT, 3)
                    .vector("rotation", FloatRepr.FLOAT, 4)
                    .build())
            .seed(ptr -> {
                MemoryUtil.memPutInt(ptr, 0xFFFFFFFF);
                ExtraMemoryOps.put2x16(ptr + 4, OverlayTexture.NO_OVERLAY);
                MemoryUtil.memPutFloat(ptr + 24, 0.5f); // pivotX
                MemoryUtil.memPutFloat(ptr + 28, 0.5f); // pivotY
                MemoryUtil.memPutFloat(ptr + 32, 0.5f); // pivotZ
                ExtraMemoryOps.putQuaternionf(ptr + 36, IDENTITY_QUAT);
            })
            .vertexShader(ResourceUtil.rl("instance/oriented.vert"))
            .cullShader(ResourceUtil.rl("instance/cull/oriented.glsl"))
            .build();

    /** {@link TransformedInstance} + per-instance slide vec3 + clip plane vec4.
     *  Used by the door variants whose animated panels disappear into their frame as they slide
     *  (SEAL, AIRLOCK, SLIDING_BLAST, CONTAINMENT). Pair with the {@code clip_slab.glsl} or
     *  {@code clip_halfspace.glsl} cutout shaders — those read the varyings emitted by this
     *  instance type's vertex shader. */
    public static final InstanceType<ClipTransformedInstance> CLIP_TRANSFORMED = SimpleInstanceType.builder(ClipTransformedInstance::new)
            .layout(LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("overlay", IntegerRepr.SHORT, 2)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .matrix("pose", FloatRepr.FLOAT, 4)
                    .vector("slide", FloatRepr.FLOAT, 3)
                    .vector("plane", FloatRepr.FLOAT, 4)
                    .build())
            .seed(ptr -> {
                MemoryUtil.memPutInt(ptr, 0xFFFFFFFF);
                ExtraMemoryOps.put2x16(ptr + 4, OverlayTexture.NO_OVERLAY);
                ExtraMemoryOps.putMatrix4f(ptr + 12, IDENTITY_M4);
                // slide + plane stay (0,0,0) + (0,0,0,0) — degenerate plane that
                // clip_slab.glsl accepts everywhere (|0| > 0 is false).
            })
            .vertexShader(ResourceUtil.rl("instance/clip_transformed.vert"))
            .cullShader(ResourceUtil.rl("instance/cull/clip_transformed.glsl"))
            .build();

    /** {@link TransformedInstance} + a per-instance atlas UV-region (offsetU, offsetV, scaleU, scaleV). All
     *  variants sharing one atlas texture collapse to one instancer; the sub-rect is per-instance. Reuses
     *  {@code cull/transformed.glsl} (it reads only {@code i.pose}, never {@code uvRegion}). */
    public static final InstanceType<UvTransformedInstance> UV_TRANSFORMED = SimpleInstanceType.builder(UvTransformedInstance::new)
            .layout(LayoutBuilder.create()
                    .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                    .vector("overlay", IntegerRepr.SHORT, 2)
                    .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                    .matrix("pose", FloatRepr.FLOAT, 4)
                    .vector("uvRegion", FloatRepr.FLOAT, 4)
                    .build())
            .seed(ptr -> {
                MemoryUtil.memPutInt(ptr, 0xFFFFFFFF);
                ExtraMemoryOps.put2x16(ptr + 4, OverlayTexture.NO_OVERLAY);
                ExtraMemoryOps.putMatrix4f(ptr + 12, IDENTITY_M4);
                // uvRegion default MUST be the identity remap (0,0,1,1); a zero-fill collapses every UV to a point.
                MemoryUtil.memPutFloat(ptr + 76, 0.0f);
                MemoryUtil.memPutFloat(ptr + 80, 0.0f);
                MemoryUtil.memPutFloat(ptr + 84, 1.0f);
                MemoryUtil.memPutFloat(ptr + 88, 1.0f);
            })
            .vertexShader(ResourceUtil.rl("instance/transformed_uv.vert"))
            .cullShader(ResourceUtil.rl("instance/cull/transformed.glsl"))
            .build();

    public static final InstanceType<ShadowInstance> SHADOW = SimpleInstanceType.builder(ShadowInstance::new)
            .layout(LayoutBuilder.create()
                    .vector("pos", FloatRepr.FLOAT, 3)
                    .vector("entityPosXZ", FloatRepr.FLOAT, 2)
                    .vector("size", FloatRepr.FLOAT, 2)
                    .scalar("alpha", FloatRepr.FLOAT)
                    .scalar("radius", FloatRepr.FLOAT)
                    .build())
            .seed(ptr -> {
            })
            .vertexShader(ResourceUtil.rl("instance/shadow.vert"))
            .cullShader(ResourceUtil.rl("instance/cull/shadow.glsl"))
            .build();

    private InstanceTypes() {
    }
}
