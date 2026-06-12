package dev.engine_room.flywheel.backend;

import com.mojang.blaze3d.platform.NativeImage;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public class NoiseTextures {
    public static final Identifier NOISE_TEXTURE = ResourceUtil.rl("textures/flywheel/noise/blue.png");

    public static DynamicTexture BLUE_NOISE;

    public static void reload(ResourceManager manager) {
        if (BLUE_NOISE != null) {
            BLUE_NOISE.close();
            BLUE_NOISE = null;
        }

        Optional<Resource> resource = manager.getResource(NOISE_TEXTURE);
        if (resource.isEmpty()) {
            return;
        }

        try (InputStream is = resource.get().open()) {
            // 26.2: no int[] getTextureData() path; read PNG directly. OIT binds an explicit repeat/linear
            // GpuSampler, so no manual GL filter/wrap mutation is needed here -- and the raw GL poke would be
            // invalid on a Vulkan host.
            NativeImage image = NativeImage.read(is);
            BLUE_NOISE = new DynamicTexture(() -> "flywheel blue noise", image);
        } catch (IOException e) {
        }
    }

}
