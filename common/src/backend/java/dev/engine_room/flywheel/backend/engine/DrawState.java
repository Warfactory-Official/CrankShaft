package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;

import java.util.Locale;

/**
 * The fixed-function state a material's draw pipeline bakes besides its programs.
 */
public record DrawState(Transparency transparency, DepthTest depthTest, boolean depthWrite, boolean colorWrite,
                        boolean cull, boolean polygonOffset) {
    public static DrawState of(Material material) {
        return new DrawState(material.transparency(), material.depthTest(), material.writeMask().depth(),
                material.writeMask().color(), material.backfaceCulling(), material.polygonOffset());
    }

    public String cacheName() {
        return "_" + transparency.name().toLowerCase(Locale.ROOT) + "_" + depthTest.name().toLowerCase(Locale.ROOT)
                + (depthWrite ? "_dw" : "") + (colorWrite ? "" : "_nc") + (cull ? "_cull" : "")
                + (polygonOffset ? "_po" : "");
    }
}
