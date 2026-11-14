package dev.engine_room.flywheel.backend.engine.far;

import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.util.ArrayList;
import java.util.List;

/**
 * Baked tiles: colour (RGBA8), normal/diffuse (RGBA8), material (RGBA32UI) and aux (RG16) texture arrays of
 * {@link #LAYER_SIZE} square layers with
 * {@link #LEVELS} levels, grown by whole layers. Rects are allocated on shelves in {@link #ALIGN}-texel units, so every
 * level of a rect stays inside it. Render thread.
 */
final class FarAtlas {
    static final int LAYER_SIZE = 1024;
    static final int LEVELS = 5;
    static final int ALIGN = 1 << (LEVELS - 1);
    private static final int MAX_LAYERS = 32;
    static final int LAYERED_NORMAL_FORMAT = GL30C.GL_RGBA16F;
    private static final int OPAQUE_BYTES_PER_TEXEL = 4 + 4 + 16 + 4;
    private static final int LAYERED_BYTES_PER_TEXEL = 8 + 8 + 16 + 8 + 8 + 4;
    // Shelf heights in units; a rect takes the lowest that holds it.
    private static final int[] CLASSES = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024};

    private final List<List<Shelf>> shelves = new ArrayList<>();
    private final IntArrayList tops = new IntArrayList();
    private int color;
    private int normal;
    private int material;
    private int aux;
    private int light;
    private int surface;
    private final boolean layered;
    private final int alignment;
    private final int unitsPerLayer;
    private final int maxLayers;
    private int layers;

    FarAtlas() {
        this(false);
    }

    FarAtlas(boolean layered) {
        this.layered = layered;
        alignment = layered ? 1 : ALIGN;
        unitsPerLayer = LAYER_SIZE / alignment;
        maxLayers = layered ? MAX_LAYERS * OPAQUE_BYTES_PER_TEXEL * 4 / (LAYERED_BYTES_PER_TEXEL * 3) : MAX_LAYERS;
        for (int i = 0; i < CLASSES.length; i++) {
            shelves.add(new ArrayList<>());
        }
    }

    int color() {
        return color;
    }

    int aux() {
        return aux;
    }

    int normal() {
        return normal;
    }

    int material() {
        return material;
    }

    int light() {
        return light;
    }

    int surface() {
        return surface;
    }

    /**
     * A rect of {@code width} by {@code height} texels (opaque rects align to {@link #ALIGN}), or {@code null} once the atlas
     * is full.
     */
    @Nullable Rect allocate(int width, int height) {
        int units = width / alignment;
        int cls = 0;
        while (CLASSES[cls] < height / alignment) {
            cls++;
        }
        for (Shelf shelf : shelves.get(cls)) {
            int x = shelf.take(units);
            if (x >= 0) {
                return new Rect(shelf, x, units);
            }
        }
        int layer = 0;
        while (layer < layers && tops.getInt(layer) + CLASSES[cls] > unitsPerLayer) {
            layer++;
        }
        if (layer == layers) {
            if (layers == maxLayers) {
                return null;
            }
            grow(Math.min(Math.max(layers * 2, 1), maxLayers));
        }
        Shelf shelf = new Shelf(layer, tops.getInt(layer), alignment, unitsPerLayer);
        tops.set(layer, shelf.y + CLASSES[cls]);
        shelves.get(cls).add(shelf);
        return new Rect(shelf, shelf.take(units), units);
    }

    void free(Rect rect) {
        rect.shelf.give(rect.x, rect.units);
    }

    void delete() {
        if (layers > 0) {
            GL11C.glDeleteTextures(color);
            GL11C.glDeleteTextures(normal);
            GL11C.glDeleteTextures(material);
            GL11C.glDeleteTextures(aux);
            if (layered) {
                GL11C.glDeleteTextures(light);
                GL11C.glDeleteTextures(surface);
            }
            FlwMemoryTracker._freeGpuMemory(bytes(layers));
        }
        layers = 0;
        tops.clear();
        shelves.forEach(List::clear);
    }

    private void grow(int newLayers) {
        int newColor = texture(layered ? GL30C.GL_RGBA16F : GL11C.GL_RGBA8, newLayers);
        int newNormal = texture(layered ? LAYERED_NORMAL_FORMAT : GL11C.GL_RGBA8, newLayers);
        int newMaterial = texture(GL30C.GL_RGBA32UI, newLayers);
        int newAux = texture(layered ? GL30C.GL_RG32F : GL30C.GL_RG16, newLayers);
        int newLight = layered ? texture(GL30C.GL_RGBA16F, newLayers) : 0;
        int newSurface = layered ? texture(GL30C.GL_R32UI, newLayers) : 0;
        if (layers > 0) {
            for (int level = 0; level < (layered ? 1 : LEVELS); level++) {
                int size = LAYER_SIZE >> level;
                GL43C.glCopyImageSubData(color, GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, newColor,
                        GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, size, size, layers);
                GL43C.glCopyImageSubData(normal, GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, newNormal,
                        GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, size, size, layers);
                GL43C.glCopyImageSubData(material, GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, newMaterial,
                        GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, size, size, layers);
                GL43C.glCopyImageSubData(aux, GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, newAux,
                        GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, size, size, layers);
                if (layered) {
                    GL43C.glCopyImageSubData(light, GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, newLight,
                            GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, size, size, layers);
                    GL43C.glCopyImageSubData(surface, GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, newSurface,
                            GL30C.GL_TEXTURE_2D_ARRAY, level, 0, 0, 0, size, size, layers);
                }
            }
            GL11C.glDeleteTextures(color);
            GL11C.glDeleteTextures(normal);
            GL11C.glDeleteTextures(material);
            GL11C.glDeleteTextures(aux);
            if (layered) {
                GL11C.glDeleteTextures(light);
                GL11C.glDeleteTextures(surface);
            }
            FlwMemoryTracker._freeGpuMemory(bytes(layers));
        }
        while (tops.size() < newLayers) {
            tops.add(0);
        }
        color = newColor;
        normal = newNormal;
        material = newMaterial;
        aux = newAux;
        light = newLight;
        surface = newSurface;
        layers = newLayers;
        FlwMemoryTracker._allocGpuMemory(bytes(layers));
    }

    private int texture(int format, int layers) {
        int texture = GL45C.glCreateTextures(GL30C.GL_TEXTURE_2D_ARRAY);
        GL45C.glTextureStorage3D(texture, layered ? 1 : LEVELS, format, LAYER_SIZE, LAYER_SIZE, layers);
        boolean integer = format == GL30C.GL_RGBA32UI || format == GL30C.GL_R32UI;
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MIN_FILTER,
                integer ? GL11C.GL_NEAREST_MIPMAP_NEAREST : GL11C.GL_LINEAR_MIPMAP_NEAREST);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_MAG_FILTER, integer ? GL11C.GL_NEAREST : GL11C.GL_LINEAR);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(texture, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(texture, GL12C.GL_TEXTURE_MAX_LEVEL, layered ? 0 : LEVELS - 1);
        return texture;
    }

    private long bytes(int layers) {
        return (long) LAYER_SIZE * LAYER_SIZE * layers
                * (layered ? LAYERED_BYTES_PER_TEXEL * 3 : OPAQUE_BYTES_PER_TEXEL * 4) / 3;
    }

    static void bindImage(int unit, int texture, int level, int access, boolean aux) {
        GL42C.glBindImageTexture(unit, texture, level, true, 0, access, aux ? GL30C.GL_RG16 : GL11C.GL_RGBA8);
    }

    void bindMaterialImage(int unit, int level, int access) {
        GL42C.glBindImageTexture(unit, material, level, true, 0, access, GL30C.GL_RGBA32UI);
    }

    /**
     * Texel origin {@code x | y << 10 | layer << 20}, as {@code FarTile.pairs} holds it.
     */
    record Rect(Shelf shelf, int x, int units) {
        int packed() {
            return x * shelf.alignment | shelf.y * shelf.alignment << 10 | shelf.layer << 20;
        }
    }

    static final class Shelf {
        private final int layer;
        private final int y;
        private final int alignment;
        // Free runs as start, end pairs in units, ascending.
        private final IntArrayList free;

        private Shelf(int layer, int y, int alignment, int units) {
            this.layer = layer;
            this.y = y;
            this.alignment = alignment;
            free = IntArrayList.of(0, units);
        }

        private int take(int units) {
            for (int i = 0; i < free.size(); i += 2) {
                int start = free.getInt(i);
                int end = free.getInt(i + 1);
                if (end - start >= units) {
                    if (end - start == units) {
                        free.removeElements(i, i + 2);
                    } else {
                        free.set(i, start + units);
                    }
                    return start;
                }
            }
            return -1;
        }

        private void give(int start, int units) {
            int end = start + units;
            int i = 0;
            while (i < free.size() && free.getInt(i) < start) {
                i += 2;
            }
            boolean joinsPrevious = i > 0 && free.getInt(i - 1) == start;
            boolean joinsNext = i < free.size() && free.getInt(i) == end;
            if (joinsPrevious && joinsNext) {
                free.set(i - 1, free.getInt(i + 1));
                free.removeElements(i, i + 2);
            } else if (joinsPrevious) {
                free.set(i - 1, end);
            } else if (joinsNext) {
                free.set(i, start);
            } else {
                free.add(i, end);
                free.add(i, start);
            }
        }
    }
}
