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

    boolean flywheel$deferredEmissive();

    boolean flywheel$deferredTranslucent();

    boolean flywheel$emissiveLight();
}
