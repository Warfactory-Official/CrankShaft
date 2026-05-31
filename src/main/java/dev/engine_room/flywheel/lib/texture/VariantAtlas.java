package dev.engine_room.flywheel.lib.texture;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import java.util.Map;

/**
 * A runtime texture atlas stitched from a bounded set of equally-sized variant skins (e.g. villager
 * profession textures), registered with the vanilla {@code TextureManager} under {@link #location()} so a
 * Flywheel {@link dev.engine_room.flywheel.api.material.Material} can reference it directly.
 *
 * <p>Downstream builds one with {@link VariantAtlasBuilder} (on the render thread), uses {@link #location()}
 * as the material texture, and per instance looks up {@link #cell(int)} (cell add order) for the source's
 * sub-rect — fed to {@code UvTransformedInstance.uvRegion} so all variants batch into one instancer.
 * {@link #cell(ResourceLocation)} serves registry-keyed sources (e.g. villager professions); unknown sources
 * return {@link Cell#IDENTITY} (full texture). The atlas has no mip chain, so its material MUST be
 * {@code mipmap(false)} (and {@code blur(false)} for a crisp NEAREST sample on the 64-aligned grid); enabling
 * mipmaps or linear filtering later would require a 1-texel inter-cell gutter + clamp to avoid bleeding.
 *
 * <p>Built and re-published on the render thread (resource reload); readers only read the published reference.
 * Call {@link #delete()} on the superseded atlas after re-publishing to free its GL texture.
 */
public final class VariantAtlas {
    /** Normalized sub-rect for one source: {@code atlasUV = (offU, offV) + baseUV * (scaleU, scaleV)}. */
    public record Cell(float offU, float offV, float scaleU, float scaleV) {
        public static final Cell IDENTITY = new Cell(0.0f, 0.0f, 1.0f, 1.0f);
    }

    private final ResourceLocation location;
    private final Map<ResourceLocation, Cell> cells;
    private final Cell[] byIndex;
    private final DynamicTexture texture;

    VariantAtlas(ResourceLocation location, Map<ResourceLocation, Cell> cells, Cell[] byIndex, DynamicTexture texture) {
        this.location = location;
        this.cells = cells;
        this.byIndex = byIndex;
        this.texture = texture;
    }

    public ResourceLocation location() {
        return location;
    }

    /** Cell by add order — the per-frame path (plain array load, no {@code ResourceLocation} hashing). */
    public Cell cell(int index) {
        return byIndex[index];
    }

    public Cell cell(ResourceLocation source) {
        return cells.getOrDefault(source, Cell.IDENTITY);
    }

    public boolean contains(ResourceLocation source) {
        return cells.containsKey(source);
    }

    public void delete() {
        texture.deleteGlTexture();
    }
}
