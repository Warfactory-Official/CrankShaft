package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.material.Material;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;

public final class MaterialRenderState {
    public static final Comparator<Material> COMPARATOR = MaterialRenderState::compare;
    /**
     * {@link #compare} minus the texture/blur/mipmap keys, so pipeline-equal draws sort adjacent.
     */
    public static final Comparator<Material> PIPELINE_COMPARATOR = MaterialRenderState::pipelineCompare;

    private MaterialRenderState() {
    }

    public static boolean materialEquals(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return true;
        }

        // Not here because they only affect the runtime-packed material (not the shader program): useLight,
        // useOverlay, diffuse, ambient occlusion. fog IS here: it is compile-time-keyed (per-material flw_fogFilter
        // splice, like cutout), so a differing fog selects a different program and must NOT share a draw batch.
        // Everything in the comparator should be here.
        return lhs.blur() == rhs.blur()
                && lhs.mipmap() == rhs.mipmap()
                && lhs.backfaceCulling() == rhs.backfaceCulling()
                && lhs.polygonOffset() == rhs.polygonOffset()
                && lhs.depthTest() == rhs.depthTest()
                && lhs.transparency() == rhs.transparency()
                && lhs.writeMask() == rhs.writeMask()
                && lhs.light().source().equals(rhs.light().source())
                && lhs.texture().equals(rhs.texture())
                && lhs.cutout().source().equals(rhs.cutout().source())
                && lhs.fog().source().equals(rhs.fog().source())
                && lhs.shaders().fragmentSource().equals(rhs.shaders().fragmentSource())
                && lhs.shaders().vertexSource().equals(rhs.shaders().vertexSource());
    }

    public static boolean materialIsAllNonNull(@Nullable Material material) {
        // We do not trust people to give us valid NotNull objects.
        return material != null &&
                material.shaders() != null &&
                material.shaders().fragmentSource() != null &&
                material.shaders().vertexSource() != null &&
                material.cutout() != null &&
                material.cutout().source() != null &&
                material.fog() != null &&
                material.fog().source() != null &&
                material.light() != null &&
                material.light().source() != null &&
                material.texture() != null &&
                material.depthTest() != null &&
                material.transparency() != null &&
                material.writeMask() != null &&
                material.cardinalLightingMode() != null;
    }

    // ---- Bindless textures: batch draws by PIPELINE-relevant state only. Texture + filter (blur/mipmap) come
    // from the global table by the draw command's slot, and the packed material rides the command too, so draws
    // that differ only in those share one pipeline and one MDI batch. ----

    public static int compare(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return 0;
        }

        int cmp;
        cmp = lhs.transparency().compareTo(rhs.transparency());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.light().source().compareTo(rhs.light().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.cutout().source().compareTo(rhs.cutout().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.fog().source().compareTo(rhs.fog().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().fragmentSource().compareTo(rhs.shaders().fragmentSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().vertexSource().compareTo(rhs.shaders().vertexSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.texture().compareTo(rhs.texture());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.blur(), rhs.blur());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.mipmap(), rhs.mipmap());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.backfaceCulling(), rhs.backfaceCulling());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.polygonOffset(), rhs.polygonOffset());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.depthTest().compareTo(rhs.depthTest());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.writeMask().compareTo(rhs.writeMask());
        return cmp;
    }

    /**
     * {@link #materialEquals} minus texture/blur/mipmap: true iff two materials select the same draw pipeline.
     */
    public static boolean pipelineEquals(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return true;
        }

        return lhs.backfaceCulling() == rhs.backfaceCulling()
                && lhs.polygonOffset() == rhs.polygonOffset()
                && lhs.depthTest() == rhs.depthTest()
                && lhs.transparency() == rhs.transparency()
                && lhs.writeMask() == rhs.writeMask()
                && lhs.light().source().equals(rhs.light().source())
                && lhs.cutout().source().equals(rhs.cutout().source())
                && lhs.fog().source().equals(rhs.fog().source())
                && lhs.shaders().fragmentSource().equals(rhs.shaders().fragmentSource())
                && lhs.shaders().vertexSource().equals(rhs.shaders().vertexSource());
    }

    private static int pipelineCompare(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return 0;
        }

        int cmp;
        cmp = lhs.transparency().compareTo(rhs.transparency());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.light().source().compareTo(rhs.light().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.cutout().source().compareTo(rhs.cutout().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.fog().source().compareTo(rhs.fog().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().fragmentSource().compareTo(rhs.shaders().fragmentSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().vertexSource().compareTo(rhs.shaders().vertexSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.backfaceCulling(), rhs.backfaceCulling());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.polygonOffset(), rhs.polygonOffset());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.depthTest().compareTo(rhs.depthTest());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.writeMask().compareTo(rhs.writeMask());
        return cmp;
    }

    /**
     * {@link #pipelineEquals} minus cutout/fog (runtime-dispatched by the uber fragment): only genuine
     * pipeline state -- light + material shaders + fixed function -- splits an uber batch.
     */
    public static boolean uberPipelineEquals(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return true;
        }

        return lhs.backfaceCulling() == rhs.backfaceCulling()
                && lhs.polygonOffset() == rhs.polygonOffset()
                && lhs.depthTest() == rhs.depthTest()
                && lhs.transparency() == rhs.transparency()
                && lhs.writeMask() == rhs.writeMask()
                && lhs.light().source().equals(rhs.light().source())
                && lhs.shaders().fragmentSource().equals(rhs.shaders().fragmentSource())
                && lhs.shaders().vertexSource().equals(rhs.shaders().vertexSource());
    }

    /**
     * {@link #pipelineCompare} minus cutout/fog; the uber draw sort key (see {@link #uberPipelineEquals}).
     */
    public static int uberPipelineCompare(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return 0;
        }

        int cmp;
        cmp = lhs.transparency().compareTo(rhs.transparency());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.light().source().compareTo(rhs.light().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().fragmentSource().compareTo(rhs.shaders().fragmentSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().vertexSource().compareTo(rhs.shaders().vertexSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.backfaceCulling(), rhs.backfaceCulling());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.polygonOffset(), rhs.polygonOffset());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.depthTest().compareTo(rhs.depthTest());
        if (cmp != 0) {
            return cmp;
        }
        return lhs.writeMask().compareTo(rhs.writeMask());
    }
}
