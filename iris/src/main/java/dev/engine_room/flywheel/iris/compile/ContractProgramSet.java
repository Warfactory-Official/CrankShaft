package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

/**
 * Mixed into Iris {@code ProgramSet}.
 */
public interface ContractProgramSet {
    /**
     * An authored or generated {@code program}, then its nearest available {@link ContractProgram#base}.
     */
    @Nullable ProgramSource flywheel$contractSource(ContractProgram program);

    /**
     * Whether the pack ships {@code program} itself, ignoring {@link ContractProgram#base}.
     */
    boolean flywheel$hasContract(ContractProgram program);

    /**
     * Native glint draws after deferred lighting; authored contracts write the gbuffer.
     */
    default boolean flywheel$nativeGlint() {
        if (!flywheel$hasContract(ContractProgram.GBUFFERS_GLINT)) return true;
        var glint = flywheel$contractSource(ContractProgram.GBUFFERS_GLINT);
        return glint.getTessControlSource().isPresent() || glint.getTessEvalSource().isPresent();
    }

    /**
     * Checked shared forward-OIT adapter for this preprocessed dimension, or null for native fallback.
     */
    @Nullable ContractProperties flywheel$nativeOit();

    @Nullable DeferredOitProfile flywheel$deferredOit();

    /**
     * Returns a validated adapter stage only after the whole dimension's plan has been accepted.
     */
    @Nullable String flywheel$patchedFragment(String program);
}
