package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
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

    void prepareFrame(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelView,
                      Vec3i renderOrigin, boolean constantAmbientLight);

    void drawOpaque(IrisRenderingPipeline pipeline);

    /**
     * Opaque casters. {@code shadowModelView}: render-origin relative; {@code camera}: the shadow pass centre.
     * {@code entities} / {@code blockEntities}: entity-tagged / other draws.
     *
     * @return whether {@link #drawShadowTranslucent} may follow in the same shadow pass
     */
    boolean drawShadow(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, Vec3i renderOrigin, Vec3 camera,
                       boolean entities, boolean blockEntities);

    void drawShadowTranslucent(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, boolean entities,
                               boolean blockEntities);

    void triggerFallback();
}
