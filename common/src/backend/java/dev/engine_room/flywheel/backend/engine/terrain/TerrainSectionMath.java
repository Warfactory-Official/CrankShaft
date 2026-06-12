package dev.engine_room.flywheel.backend.engine.terrain;

import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class TerrainSectionMath {
    public static final double FADE_NEAR_DISTANCE_SQ = 768.0;

    private TerrainSectionMath() {
    }

    public static long computeFadeDuration(int originX, int originY, int originZ) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double cx = originX + 8.0 - cam.x;
        double cy = originY + 8.0 - cam.y;
        double cz = originZ + 8.0 - cam.z;
        if (cx * cx + cy * cy + cz * cz < FADE_NEAR_DISTANCE_SQ) {
            return 0L;
        }
        return Mth.floor(mc.options.chunkSectionFadeInTime().get() * 1000.0);
    }

    public static long sumVertexCount(long pMeshData) {
        long vertexCount = 0;
        for (int g = 0; g < ModelQuadFacing.COUNT; g++) {
            vertexCount += SectionRenderDataUnsafe.getVertexCount(pMeshData, g);
        }
        return vertexCount;
    }

    public static int localSectionX(int s) {
        return (s >> 5) & 0x7;
    }

    public static int localSectionY(int s) {
        return s & 0x3;
    }

    public static int localSectionZ(int s) {
        return (s >> 2) & 0x7;
    }
}
