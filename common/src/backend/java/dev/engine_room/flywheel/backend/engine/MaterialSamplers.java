package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * The material-texture sampler policy, shared by every draw path. The BLOCK ATLAS honors the material's declared
 * blur/mipmap with NEAREST magnification and clamped wrap: the {@code Materials.*_BLOCK} family declares blur
 * (vanilla's moving-block sampler), the {@code *_BLOCK_ITEM} family non-blur non-mip (vanilla binds the atlas's own
 * unmipmapped NEAREST sampler). The ITEM ATLAS is never mip-sampled by vanilla, so mips are forced off; clamped for
 * atlas-border safety. Every other material texture honors its declared blur/mipmap with REPEAT wrap -- the vanilla
 * default, and required by UV-transforming shaders (the {@code glint*.vert}s).
 */
public final class MaterialSamplers {
    // Entity-shadow gradient UVs run outside [0,1]; vanilla clamps per fragment, so REPEAT would tile -- clamp-to-edge is the equivalent.
    private static final Identifier ENTITY_SHADOW = Identifier.withDefaultNamespace("textures/misc/shadow.png");

    private MaterialSamplers() {
    }

    public static GpuSampler get(Identifier texture, boolean blur, boolean mipmap) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(texture)) {
            return RenderSystem.getSamplerCache()
                               .getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                                       blur ? FilterMode.LINEAR : FilterMode.NEAREST, FilterMode.NEAREST, mipmap);
        }
        if (TextureAtlas.LOCATION_ITEMS.equals(texture) || ENTITY_SHADOW.equals(texture)) {
            return RenderSystem.getSamplerCache()
                               .getClampToEdge(blur ? FilterMode.LINEAR : FilterMode.NEAREST, false);
        }
        return RenderSystem.getSamplerCache()
                           .getRepeat(blur ? FilterMode.LINEAR : FilterMode.NEAREST, mipmap);
    }

    public static GpuSampler get(Material material) {
        return get(material.texture(), material.blur(), material.mipmap());
    }
}
