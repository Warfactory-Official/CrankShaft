// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;

public final class MeshFeatureConfig {
    private static final int BIT_RGSS = 1;
    private static final int BIT_BARYCENTRIC = 1 << 1;

    // OFF by default: benchmarking found it neutral-to-regression. A future config option flips this.
    private static volatile boolean barycentricEnabled = false;

    private MeshFeatureConfig() {
    }

    public static void setBarycentricEnabled(boolean enabled) {
        barycentricEnabled = enabled;
    }

    public static int currentKey() {
        Options o = Minecraft.getInstance().options;
        int key = 0;
        if (!TerrainAtlasFilter.linear() && o.textureFiltering().get() == TextureFilteringMethod.RGSS) {
            key |= BIT_RGSS;
        }
        if (barycentricEnabled) {
            key |= BIT_BARYCENTRIC;
        }
        return key;
    }

    public static TextureFilteringMethod atlasFilter() {
        return Minecraft.getInstance().options.textureFiltering().get();
    }

    public static int atlasAnisotropy() {
        return Minecraft.getInstance().options.maxAnisotropyValue();
    }

    public static void applyFeatureDefines(Compilation ctx, int key) {
        if ((key & BIT_RGSS) != 0) {
            ctx.define("MESHLET_RGSS");
        }
        if ((key & BIT_BARYCENTRIC) != 0) {
            ctx.define("MESHLET_BARYCENTRIC");
        }
    }

    public static void applyFragExtensions(Compilation ctx, int key) {
        if ((key & BIT_BARYCENTRIC) != 0) {
            ctx.requireExtension("GL_NV_fragment_shader_barycentric");
            ctx.requireExtension("GL_NV_gpu_shader5");
            ctx.requireExtension("GL_NV_shader_buffer_load");
        }
    }
}
