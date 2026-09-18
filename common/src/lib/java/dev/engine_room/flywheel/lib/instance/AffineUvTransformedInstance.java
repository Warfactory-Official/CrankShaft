package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Affine pose with a full two-dimensional UV transform. Both loaders use the same layout; owning visual
 * tasks mutate instances before the render barrier. Geometry buffers stay immutable as triangles deform.
 */
public final class AffineUvTransformedInstance extends UvTransformedInstance {    private static final int OFF_UV_SHEAR = 92;
    private static final Matrix4f IDENTITY = new Matrix4f();

    public static final InstanceType<AffineUvTransformedInstance> TYPE = SimpleInstanceType
            .builder(AffineUvTransformedInstance::new)
            .layout(LayoutBuilder.create()
                                 .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                                 .vector("overlay", IntegerRepr.SHORT, 2)
                                 .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                                 .matrix("pose", FloatRepr.FLOAT, 4)
                                 .vector("uvRegion", FloatRepr.FLOAT, 4)
                                 .vector("uvShear", FloatRepr.FLOAT, 2).build())
            .seed(ptr -> {
                MemoryUtil.memPutInt(ptr + ColoredLitInstance.OFF_RGBA, 0xFFFFFFFF);
                ExtraMemoryOps.put2x16(ptr + ColoredLitOverlayInstance.OFF_OVERLAY, OverlayTexture.NO_OVERLAY);
                ExtraMemoryOps.putMatrix4f(ptr + TransformedInstance.OFF_POSE, IDENTITY);
                ExtraMemoryOps.putVector4f(ptr + OFF_UV_REGION, 0, 0, 1, 1);
                MemoryUtil.memPutFloat(ptr + OFF_UV_SHEAR, 0);
                MemoryUtil.memPutFloat(ptr + OFF_UV_SHEAR + 4, 0);
            })
            .vertexShader(ResourceUtil.rl("instance/transformed_affine_uv.vert"))
            .cullShader(ResourceUtil.rl("instance/cull/transformed.glsl")).build();

    public AffineUvTransformedInstance(InstanceType<? extends AffineUvTransformedInstance> type,
                                       InstanceHandle handle) {
        super(type, handle);
    }

    public AffineUvTransformedInstance uv(float m00, float m01, float m10, float m11, float offsetU, float offsetV) {
        super.uvRegion(offsetU, offsetV, m00, m11);
        MemoryUtil.memPutFloat(slabPtr() + OFF_UV_SHEAR, m01);
        MemoryUtil.memPutFloat(slabPtr() + OFF_UV_SHEAR + 4, m10);
        return this;
    }

    @Override
    public AffineUvTransformedInstance uvRegion(float offsetU, float offsetV, float scaleU, float scaleV) {
        return uv(scaleU, 0, 0, scaleV, offsetU, offsetV);
    }
}
