package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import org.jspecify.annotations.Nullable;

/**
 * Mixed into Iris {@code ShaderPack}.
 */
public interface ContractShaderPack {
    ContractProperties flywheel$contractProperties();

    boolean flywheel$forwardOit();

    @Nullable DeferredOitProfile flywheel$deferredOit();

    @Nullable DeferredOitProfile flywheel$forwardProfile();

    boolean flywheel$deferredEmissive();

    boolean flywheel$deferredTranslucent();

    boolean flywheel$emissiveLight();

    /**
     * Whether the pack's programs take Minecraft's raw reversed depth and projections instead of Iris's forward
     * conversion.
     */
    boolean flywheel$nativeDepth();

    /**
     * Super Resolution's {@code SR_RENDER_SCALE_FACTOR} the pack's sources were preprocessed with; 1 without it.
     */
    float flywheel$renderScale();
}
