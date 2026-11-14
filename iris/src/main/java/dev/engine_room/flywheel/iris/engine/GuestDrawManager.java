package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.SubmissionOrder;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.iris.compile.PackRole;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
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
                || kind == TaggedEnvironment.KIND_ENTITY_BLENDED
                || kind == TaggedEnvironment.KIND_ENTITY_PARTICLES_TRANSLUCENT;
    }

    /**
     * Iris keeps a vanilla-routed layer's depth state in the shadow pass too, so a colour-only one gets no depth-only
     * redraw and casts no shadow.
     */
    static boolean depthless(Material material, int drawTag) {
        return routed(drawTag) && !translucentParticle(drawTag) && !material.writeMask().depth();
    }

    /**
     * Vanilla's translucent particles: depth in their colour pass, so later draws of the pass test against it and they
     * need no depth-only redraw; Iris's shadow renderer draws no particles.
     */
    static boolean translucentParticle(int drawTag) {
        return TaggedEnvironment.kind(drawTag) == TaggedEnvironment.KIND_ENTITY_PARTICLES_TRANSLUCENT;
    }

    // Vanilla draws blended entity render types after the opaque ones, which Iris follows with its deferred passes.
    static boolean drawnInTranslucentPass(Material material, int drawTag) {
        int kind = TaggedEnvironment.kind(drawTag);
        return material.transparency() == Transparency.TRANSLUCENT
                || material.transparency() == Transparency.TRANSLUCENT_ALPHA_REPLACE
                || OitTransparency.orderIndependent(material) || kind == TaggedEnvironment.KIND_ENTITY_TRANSLUCENT
                || kind == TaggedEnvironment.KIND_ENTITY_BLENDED
                || kind == TaggedEnvironment.KIND_ENTITY_PARTICLES_TRANSLUCENT
                || material.transparency() == Transparency.GLINT && GuestPipelines.nativeGlint();
    }

    static boolean drawnInAdditivePass(Material material, int drawTag) {
        return emissive(material) && !routed(drawTag);
    }

    /**
     * Pack programs choose their own blending, and order-independent draws blend in order wherever the pack has no
     * OIT contract.
     */
    static boolean keepsSubmissionOrder(Material material, int drawTag) {
        return material.transparency() != Transparency.OPAQUE || routed(drawTag) || SubmissionOrder.matters(material);
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

    static boolean lateLight(Material material) {
        return material.depthTest() == DepthTest.LEQUAL && !material.writeMask().depth()
                && material.writeMask().color();
    }

    void prepareFrame(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelView,
                      Vec3i renderOrigin, boolean constantAmbientLight);

    void drawOpaque(IrisRenderingPipeline pipeline);

    void drawLateLight();

    default void drawLateBillboards() {
    }

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
