// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

package me.mlbv.meshlet.mesh.shared;

import dev.engine_room.flywheel.backend.compile.core.Compilation;

public final class MeshShaderPrep {
    public static final int VERTEX_STRIDE = 20;

    private MeshShaderPrep() {
    }

    public static void applyGlobalDefines(Compilation ctx) {
        if (Boolean.getBoolean("meshlet.debug")) {
            ctx.define("DEBUG");
        }
        if (Boolean.parseBoolean(System.getProperty("meshlet.renderFog", "true"))) {
            ctx.define("RENDER_FOG");
        }
        if (Boolean.parseBoolean(System.getProperty("meshlet.cullDegenerateTriangles", "true"))) {
            ctx.define("CULL_DEGENERATE_TRIANGLES");
        }
        if (!Boolean.getBoolean("meshlet.customTranslucencySort")) {
            ctx.define("TRANSLUCENCY_SORTING_SODIUM");
        }
        ctx.define("MAX_MESHLET_QUADS", String.valueOf(Integer.getInteger("meshlet.maxMeshletQuads", 16)));
    }
}
