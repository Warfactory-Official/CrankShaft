package dev.engine_room.flywheel.lib.transform;

import com.mojang.blaze3d.vertex.PoseStack;

public interface TransformStack<Self extends TransformStack<Self>> extends Transform<Self> {
    /**
     * Wrap a {@link PoseStack} as a {@link TransformStack}. The wrapper is stateless -- creating a new instance per call is safe.
     */
    static PoseTransformStack of(PoseStack stack) {
        return new PoseTransformStack(stack);
    }

    Self pushPose();

    Self popPose();
}
