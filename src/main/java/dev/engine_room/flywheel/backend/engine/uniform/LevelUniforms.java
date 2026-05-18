package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import org.joml.Vector3f;

public final class LevelUniforms extends UniformWriter {
    // 4 vec4 slots (skyColor, cloudColor, light0, light1) + 13 scalar slots.
    private static final int SIZE = 16 * 4 + 4 * 13;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.LEVEL_INDEX, SIZE);

    // Match vanilla RenderHelper.LIGHT0_POS / LIGHT1_POS (the two directional lights
    // installed by enableStandardItemLighting). Without these set, every dot product in
    // diffuseFromLightDirections is zero and the CardinalLightingMode.ENTITY shader path
    // (default for SimpleMaterial — used by SOLID_BLOCK, hence vanilla chest et al.)
    // collapses to the constant floor 0.4 → meshes render flat-dim with no face shading.
    public static final Vector3f LIGHT0_DIRECTION = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    public static final Vector3f LIGHT1_DIRECTION = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();

    private LevelUniforms() {
    }

    public static void update(RenderContext context) {
        long ptr = BUFFER.ptr();

        WorldClient level = context.level();
        float partialTick = context.partialTick();

        Entity viewEntity = context.camera().getEntity();
        Vec3d skyColor = level.getSkyColor(viewEntity, partialTick);
        Vec3d cloudColor = level.getCloudColour(partialTick);
        ptr = writeVec4(ptr, (float) skyColor.x, (float) skyColor.y, (float) skyColor.z, 1f);
        ptr = writeVec4(ptr, (float) cloudColor.x, (float) cloudColor.y, (float) cloudColor.z, 1f);

        ptr = writeVec3(ptr, LIGHT0_DIRECTION);
        ptr = writeVec3(ptr, LIGHT1_DIRECTION);

        long dayTime = level.getWorldTime();
        long levelDay = dayTime / 24000L;
        float timeOfDay = (float) (dayTime - levelDay * 24000L) / 24000f;
        ptr = writeInt(ptr, (int) (levelDay % 0x7FFFFFFFL));
        ptr = writeFloat(ptr, timeOfDay);

        ptr = writeInt(ptr, level.provider.hasSkyLight() ? 1 : 0);

        ptr = writeFloat(ptr, level.getCelestialAngle(partialTick));

        ptr = writeFloat(ptr, level.getCurrentMoonPhaseFactor());
        ptr = writeInt(ptr, level.getMoonPhase());

        ptr = writeInt(ptr, level.isRaining() ? 1 : 0);
        ptr = writeFloat(ptr, level.getRainStrength(partialTick));
        ptr = writeInt(ptr, level.isThundering() ? 1 : 0);
        ptr = writeFloat(ptr, level.getThunderStrength(partialTick));

        ptr = writeFloat(ptr, level.getSunBrightness(partialTick));

        // 1.12.2 has no per-dimension constantAmbientLight flag (upstream reads
        // ClientLevel.effects().constantAmbientLight()). The actual physical property — a
        // non-zero brightness floor at light level 0 — lives in WorldProvider's brightness
        // table: Nether sets [0] = 0.1f; Overworld/End set [0] = 0.0f. Modded dimensions
        // that copy the Nether-style curve get the correct flag automatically.
        ptr = writeInt(ptr, level.provider.getLightBrightnessTable()[0] > 0.0F ? 1 : 0);

        DimensionType dimension = level.provider.getDimensionType();
        int dimensionId;
        if (dimension == DimensionType.OVERWORLD) {
            dimensionId = 0;
        } else if (dimension == DimensionType.NETHER) {
            dimensionId = 1;
        } else if (dimension == DimensionType.THE_END) {
            dimensionId = 2;
        } else {
            dimensionId = -1;
        }
        ptr = writeInt(ptr, dimensionId);

        BUFFER.markDirty();
    }
}
