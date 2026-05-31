package dev.engine_room.flywheel.lib.texture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link VariantAtlas} by stitching equally-sized cells into a square-ish grid. A cell is either a
 * single source skin ({@link #add}) or a stack of layers composited bottom-to-top ({@link #addLayered}, e.g. a
 * horse coat with a markings overlay — the runtime equivalent of vanilla {@code LayeredTexture}). All cells MUST
 * share the first cell's dimensions (fail-fast). {@link #build()} composites, uploads, and registers the atlas —
 * it makes GL calls, so it MUST run on the client/render thread (e.g. inside a resource-reload listener). Cells
 * are placed in add order.
 */
public final class VariantAtlasBuilder {
    private record Entry(ResourceLocation key, ResourceLocation[] layers) {}

    private final ResourceLocation location;
    private final List<Entry> entries = new ArrayList<>();

    public VariantAtlasBuilder(ResourceLocation location) {
        this.location = location;
    }

    /** A single-source cell, keyed by its own location. */
    public VariantAtlasBuilder add(ResourceLocation source) {
        entries.add(new Entry(source, new ResourceLocation[] { source }));
        return this;
    }

    /** A composited cell, keyed by {@code key}: {@code layers} are drawn bottom-to-top (base verbatim, overlays
     *  alpha-blended over). Null layers are skipped — pass a {@code null} overlay for the "no markings" variant. */
    public VariantAtlasBuilder addLayered(ResourceLocation key, ResourceLocation... layers) {
        entries.add(new Entry(key, layers));
        return this;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public VariantAtlas build() {
        int n = entries.size();
        if (n == 0) {
            throw new IllegalStateException("VariantAtlasBuilder has no cells: " + location);
        }

        BufferedImage[] cells = new BufferedImage[n];
        for (int i = 0; i < n; i++) {
            cells[i] = composite(entries.get(i));
        }
        int cellW = cells[0].getWidth();
        int cellH = cells[0].getHeight();
        for (int i = 1; i < n; i++) {
            if (cells[i].getWidth() != cellW || cells[i].getHeight() != cellH) {
                throw new IllegalStateException("VariantAtlas cell " + entries.get(i).key() + " is "
                        + cells[i].getWidth() + "x" + cells[i].getHeight() + ", expected " + cellW + "x" + cellH);
            }
        }

        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        int atlasW = cols * cellW;
        int atlasH = rows * cellH;

        BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        // Src (not SrcOver) copies cell alpha verbatim into the transparent atlas — no premultiply into the
        // background, so cutout (alpha-0) texels stay alpha-0.
        g.setComposite(AlphaComposite.Src);
        Map<ResourceLocation, VariantAtlas.Cell> cellMap = new HashMap<>(n * 2);
        VariantAtlas.Cell[] byIndex = new VariantAtlas.Cell[n];
        for (int i = 0; i < n; i++) {
            int cx = i % cols;
            int cy = i / cols;
            g.drawImage(cells[i], cx * cellW, cy * cellH, null);
            byIndex[i] = new VariantAtlas.Cell(
                    (cx * cellW) / (float) atlasW, (cy * cellH) / (float) atlasH,
                    cellW / (float) atlasW, cellH / (float) atlasH);
            cellMap.put(entries.get(i).key(), byIndex[i]);
        }
        g.dispose();

        DynamicTexture texture = new DynamicTexture(atlas);
        Minecraft.getMinecraft().getTextureManager().loadTexture(location, texture);
        return new VariantAtlas(location, cellMap, byIndex, texture);
    }

    private static BufferedImage composite(Entry e) {
        List<BufferedImage> layers = new ArrayList<>(e.layers().length);
        for (ResourceLocation layer : e.layers()) {
            if (layer != null) {
                layers.add(read(layer));
            }
        }
        if (layers.isEmpty()) {
            throw new IllegalStateException("VariantAtlas cell " + e.key() + " has no non-null layers");
        }
        if (layers.size() == 1) {
            return layers.get(0);
        }

        BufferedImage base = layers.get(0);
        BufferedImage cell = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cell.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(base, 0, 0, null);
        g.setComposite(AlphaComposite.SrcOver);
        for (int i = 1; i < layers.size(); i++) {
            g.drawImage(layers.get(i), 0, 0, null);
        }
        g.dispose();
        return cell;
    }

    private static BufferedImage read(ResourceLocation source) {
        try (IResource res = Minecraft.getMinecraft().getResourceManager().getResource(source)) {
            return TextureUtil.readBufferedImage(res.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read variant atlas source " + source, e);
        }
    }
}
