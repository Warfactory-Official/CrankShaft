package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceTransform;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

public final class InstanceTransforms {
    public static final InstanceTransform TRANSFORMED = (ptr, _, pose) -> matrix(ptr + TransformedInstance.OFF_POSE,
            pose);
    public static final InstanceTransform CLIP_TRANSFORMED = (ptr, _, pose) -> {
        matrix(ptr + TransformedInstance.OFF_POSE, pose);
        pose.translate(f(ptr, ClipTransformedInstance.OFF_SLIDE), f(ptr, ClipTransformedInstance.OFF_SLIDE + 4),
                f(ptr, ClipTransformedInstance.OFF_SLIDE + 8));
    };
    public static final InstanceTransform ORIENTED = (ptr, _, pose) -> {
        float x = f(ptr, 36), y = f(ptr, 40), z = f(ptr, 44), w = f(ptr, 48);
        pose.identity().m00(1 - 2 * (y * y + z * z)).m01(2 * (x * y + w * z)).m02(2 * (x * z - w * y))
            .m10(2 * (x * y - w * z)).m11(1 - 2 * (x * x + z * z)).m12(2 * (y * z + w * x))
            .m20(2 * (x * z + w * y)).m21(2 * (y * z - w * x)).m22(1 - 2 * (x * x + y * y));
        float px = f(ptr, 24), py = f(ptr, 28), pz = f(ptr, 32);
        pose.m30(f(ptr, 12) + px).m31(f(ptr, 16) + py).m32(f(ptr, 20) + pz).translate(-px, -py, -pz);
    };
    public static final InstanceTransform BILLBOARD = new InstanceTransform() {
        @Override
        public void write(long ptr, Matrix4fc view, Matrix4f pose) {
            billboard(view, pose).scale(f(ptr, BillboardInstance.OFF_SIZE));
            pose.m30(f(ptr, 12)).m31(f(ptr, 16)).m32(f(ptr, 20));
        }

        @Override
        public boolean viewDependent() {
            return true;
        }
    };
    public static final InstanceTransform GLYPH = new InstanceTransform() {
        @Override
        public void write(long ptr, Matrix4fc view, Matrix4f pose) {
            float shear = f(ptr, 36);
            billboard(view, pose).translate((f(ptr, 20) + shear) * .025F, f(ptr, 24) * -.025F, f(ptr, 56) * .025F);
            pose.m30(pose.m30() + f(ptr, 8)).m31(pose.m31() + f(ptr, 12)).m32(pose.m32() + f(ptr, 16));
            pose.scale(f(ptr, 28) * .025F, f(ptr, 32) * -.025F, 0);
            // x additionally depends on the source y before the billboard transform.
            pose.m10(pose.m10() + view.m00() * shear * -.05F)
                .m11(pose.m11() + view.m10() * shear * -.05F)
                .m12(pose.m12() + view.m20() * shear * -.05F);
        }

        @Override
        public boolean viewDependent() {
            return true;
        }
    };
    public static final InstanceTransform SHADOW = (ptr, _, pose) ->
            pose.translation(f(ptr, 0), f(ptr, 4), f(ptr, 8)).scale(f(ptr, 20), 1, f(ptr, 24));

    private InstanceTransforms() {
    }

    private static float f(long ptr, int offset) {
        return MemoryUtil.memGetFloat(ptr + offset);
    }

    private static Matrix4f billboard(Matrix4fc view, Matrix4f pose) {
        return pose.identity().m00(view.m00()).m01(view.m10()).m02(view.m20())
                   .m10(view.m01()).m11(view.m11()).m12(view.m21())
                   .m20(view.m02()).m21(view.m12()).m22(view.m22());
    }

    private static void matrix(long ptr, Matrix4f pose) {
        pose.set(f(ptr, 0), f(ptr, 4), f(ptr, 8), f(ptr, 12),
                f(ptr, 16), f(ptr, 20), f(ptr, 24), f(ptr, 28),
                f(ptr, 32), f(ptr, 36), f(ptr, 40), f(ptr, 44),
                f(ptr, 48), f(ptr, 52), f(ptr, 56), f(ptr, 60));
    }
}
