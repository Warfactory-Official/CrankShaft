package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3f;

public final class LevelUniforms extends UniformWriter {
    // Populated each frame by LightingMixin from vanilla Lighting's world-space LEVEL diffuse dirs.
    public static final Vector3f LIGHT0_DIRECTION = new Vector3f();
    public static final Vector3f LIGHT1_DIRECTION = new Vector3f();
    private static final int SIZE = 16 * 4 + 4 * 13;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.LEVEL_INDEX, SIZE);

    private LevelUniforms() {
    }

    public static void update(RenderContext context) {
        long ptr = BUFFER.ptr();

        ClientLevel level = context.level();
        float partialTick = context.partialTick();
        Camera camera = context.camera();

        int skyColor = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partialTick);
        int cloudColor = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, partialTick);
        ptr = writeVec4(ptr, ARGB.redFloat(skyColor), ARGB.greenFloat(skyColor), ARGB.blueFloat(skyColor), 1f);
        ptr = writeVec4(ptr, ARGB.redFloat(cloudColor), ARGB.greenFloat(cloudColor), ARGB.blueFloat(cloudColor), 1f);

        ptr = writeVec3(ptr, LIGHT0_DIRECTION);
        ptr = writeVec3(ptr, LIGHT1_DIRECTION);

        long dayTime = level.getDefaultClockTime();
        long levelDay = dayTime / 24000L;
        float timeOfDay = (float) (dayTime - levelDay * 24000L) / 24000f;
        ptr = writeInt(ptr, (int) (levelDay % 0x7FFFFFFFL));
        ptr = writeFloat(ptr, timeOfDay);

        ptr = writeInt(ptr, level.dimensionType().hasSkyLight() ? 1 : 0);

        ptr = writeFloat(ptr, camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE,
                partialTick) * (float) (Math.PI / 180.0));

        MoonPhase moonPhase = camera.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, partialTick);
        ptr = writeFloat(ptr, DimensionType.MOON_BRIGHTNESS_PER_PHASE[moonPhase.index()]);
        ptr = writeInt(ptr, moonPhase.index());

        ptr = writeInt(ptr, level.isRaining() ? 1 : 0);
        ptr = writeFloat(ptr, level.getRainLevel(partialTick));
        ptr = writeInt(ptr, level.isThundering() ? 1 : 0);
        ptr = writeFloat(ptr, level.getThunderLevel(partialTick));

        ptr = writeFloat(ptr, camera.attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partialTick));

        ptr = writeInt(ptr, level.dimensionType().ambientLight() > 0.0F ? 1 : 0);

        int dimensionId;
        ResourceKey<Level> dimension = level.dimension();
        if (Level.OVERWORLD.equals(dimension)) {
            dimensionId = 0;
        } else if (Level.NETHER.equals(dimension)) {
            dimensionId = 1;
        } else if (Level.END.equals(dimension)) {
            dimensionId = 2;
        } else {
            dimensionId = -1;
        }
        ptr = writeInt(ptr, dimensionId);

        BUFFER.markDirty();
    }
}
