package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;

/**
 * Registry sizes a generated uber program covers. Instance types, cutouts and fogs only append, so a program built for
 * one generation draws every draw whose {@link #required} generation it {@link #covers}.
 */
public record ProgramGeneration(int types, int cutouts, int fogs) {
    /**
     * Programs keyed by neither registry.
     */
    public static final ProgramGeneration NONE = new ProgramGeneration(0, 0, 0);
    /**
     * Covers every draw.
     */
    public static final ProgramGeneration UNBOUNDED = new ProgramGeneration(Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MAX_VALUE);
    private static ProgramGeneration current = NONE;

    /**
     * Render thread.
     */
    public static ProgramGeneration current() {
        int types = InstanceTypeIds.snapshot().types().size();
        int cutouts = MaterialShaderIndices.cutoutSources().all().size();
        int fogs = MaterialShaderIndices.fogSources().all().size();
        ProgramGeneration last = current;
        if (last.types != types || last.cutouts != cutouts || last.fogs != fogs) {
            current = last = new ProgramGeneration(types, cutouts, fogs);
        }
        return last;
    }

    /**
     * The generation a draw of instance type {@code typeId} with these material shader indices needs.
     */
    public static ProgramGeneration required(int typeId, int cutoutIndex, int fogIndex) {
        return new ProgramGeneration(typeId + 1, cutoutIndex + 1, fogIndex + 1);
    }

    public boolean covers(ProgramGeneration required) {
        return required.types <= types && required.cutouts <= cutouts && required.fogs <= fogs;
    }

    public ProgramGeneration min(ProgramGeneration other) {
        if (types <= other.types && cutouts <= other.cutouts && fogs <= other.fogs) return this;
        return new ProgramGeneration(Math.min(types, other.types), Math.min(cutouts, other.cutouts),
                Math.min(fogs, other.fogs));
    }

    public ProgramGeneration max(ProgramGeneration other) {
        if (other.types <= types && other.cutouts <= cutouts && other.fogs <= fogs) return this;
        return new ProgramGeneration(Math.max(types, other.types), Math.max(cutouts, other.cutouts),
                Math.max(fogs, other.fogs));
    }

    /**
     * The material registries alone, for programs compiled per instance type.
     */
    public ProgramGeneration materials() {
        return new ProgramGeneration(Integer.MAX_VALUE, cutouts, fogs);
    }

    /**
     * Also limited to the instance types a cull program covers.
     */
    public ProgramGeneration culledBy(int cullTypes) {
        return cullTypes >= types ? this : new ProgramGeneration(cullTypes, cutouts, fogs);
    }
}
