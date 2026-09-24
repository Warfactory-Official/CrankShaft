package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.iris.compile.PackRole;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

interface GuestDrawManager {
    /**
     * Colorwheel's OIT contract has no emission flavour, so {@code ORDER_INDEPENDENT_ADDITIVE} takes the
     * {@code Transparency} fallback (drawn as {@code LIGHTNING}) rather than the guest OIT chain, whose targets are
     * sized from the translucent program alone.
     */
    static boolean orderIndependent(Material material) {
        return OitTransparency.orderIndependent(material) && !OitTransparency.additive(material);
    }

    // Iris draws program-routed entity layers plain-blended.
    static boolean orderIndependent(Material material, int drawTag) {
        return orderIndependent(material) && !routed(drawTag);
    }

    static boolean routed(int drawTag) {
        int kind = TaggedEnvironment.kind(drawTag);
        return kind == TaggedEnvironment.KIND_ENTITY_EYES || kind == TaggedEnvironment.KIND_ENTITY_TRANSLUCENT
                || kind == TaggedEnvironment.KIND_ENTITY_BLENDED;
    }

    // Vanilla draws blended entity render types after the opaque ones, which Iris follows with its deferred passes.
    static boolean drawnInTranslucentPass(Material material, int drawTag) {
        int kind = TaggedEnvironment.kind(drawTag);
        return material.transparency() == Transparency.TRANSLUCENT
                || material.transparency() == Transparency.TRANSLUCENT_ALPHA_REPLACE
                || OitTransparency.orderIndependent(material) || kind == TaggedEnvironment.KIND_ENTITY_TRANSLUCENT
                || kind == TaggedEnvironment.KIND_ENTITY_BLENDED;
    }

    static boolean drawnInAdditivePass(Material material, int drawTag) {
        return emissive(material) && !routed(drawTag);
    }

    /**
     * Draws that add light rather than cover what is behind them, so {@link PackRole#ADDITIVE} resolves their
     * program. One seam for all: translucent, else before a forward pack's composite their colour is relit as albedo;
     * {@code GuestPipelines.deferredEmissive} moves them before the deferred passes. Never shadow casters.
     */
    static boolean emissive(Material material) {
        Transparency transparency = material.transparency();
        return transparency == Transparency.ADDITIVE || transparency == Transparency.LIGHTNING
                || transparency == Transparency.ORDER_INDEPENDENT_ADDITIVE;
    }

    void prepareFrame(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelView,
                      Vec3i renderOrigin, boolean constantAmbientLight);

    void drawOpaque(IrisRenderingPipeline pipeline);

    /**
     * Opaque casters. {@code shadowModelView}: render-origin relative; {@code camera}: the shadow pass centre.
     * {@code entities} / {@code blockEntities}: whether entity- / block-entity-tagged draws cast; other draws always do.
     *
     * @return whether {@link #drawShadowTranslucent} may follow in the same shadow pass
     */
    boolean drawShadow(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, Vec3i renderOrigin, Vec3 camera,
                       boolean entities, boolean blockEntities);

    void drawShadowTranslucent(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, boolean entities,
                               boolean blockEntities);

    void triggerFallback();
}
