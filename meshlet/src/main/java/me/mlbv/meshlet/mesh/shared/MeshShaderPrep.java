// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

package me.mlbv.meshlet.mesh.shared;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;

public final class MeshShaderPrep {
    private MeshShaderPrep() {
    }

    public static void applyGlobalDefines(Compilation ctx) {
        // Sizes the arena's Vertex struct; every fetch indexes by it, so it must follow the live chunk format.
        TerrainVertexFormat.appendDefines(ctx);
        if (Boolean.getBoolean("meshlet.debug")) {
            ctx.define("DEBUG");
        }
        ctx.define("RENDER_FOG");
        ctx.define("CULL_DEGENERATE_TRIANGLES");
        ctx.define("TRANSLUCENCY_SORTING_SODIUM");
        ctx.define("MAX_MESHLET_QUADS", "16");
    }
}
