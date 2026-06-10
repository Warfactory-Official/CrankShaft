package dev.engine_room.flywheel.lib.visual.component;

import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.GlyphInstance;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Random;

/**
 * Instanced vanilla nameplate ({@code EntityRenderer.drawNameplate} + {@code FontRenderer} layout):
 * glyph quads, style rects and the background box become {@code GLYPH} instances billboarded about a
 * shared anchor, so the per-frame cost of a static label is anchor/light writes only. The owner
 * evaluates vanilla's {@code canRenderName} rules and passes the label text per frame; obfuscated
 * (§k) text relayouts every frame like vanilla.
 */
public final class NameTagComponent {
    // FontRenderer's ascii.png glyph table (index = atlas cell), verbatim.
    private static final String ASCII_TABLE = "\u00c0\u00c1\u00c2\u00c8\u00ca\u00cb\u00cd\u00d3\u00d4\u00d5\u00da\u00df\u00e3\u00f5\u011f\u0130\u0131\u0152\u0153\u015e\u015f\u0174\u0175\u017e\u0207\u0000\u0000\u0000\u0000\u0000\u0000\u0000 !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u0000\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00f8\u00a3\u00d8\u00d7\u0192\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u00ae\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580\u03b1\u03b2\u0393\u03c0\u03a3\u03c3\u03bc\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u2205\u2208\u2229\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u0000";
    private static final String FORMATTING_CODES = "0123456789abcdefklmnor";
    private static final ResourceLocation ASCII_TEXTURE = new ResourceLocation("textures/font/ascii.png");
    private static final ResourceLocation WHITE_TEXTURE = new ResourceLocation("flywheel", "textures/flywheel/white.png");
    private static final ResourceLocation[] UNICODE_PAGES = new ResourceLocation[256];
    private static final GlyphInstance[] EMPTY = new GlyphInstance[0];

    // Vanilla's two passes: see-through (depth test and writes off) then solid (tested, written).
    // Sneaking keeps the box depth-tested but never depth-written.
    private static final Material WHITE_SEETHROUGH_MATERIAL = SimpleMaterial.builder()
            .texture(WHITE_TEXTURE)
            .transparency(Transparency.TRANSLUCENT)
            .depthTest(DepthTest.OFF)
            .writeMask(WriteMask.COLOR)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .useOverlay(false)
            .mipmap(false)
            .build();
    private static final Material WHITE_SNEAK_MATERIAL = SimpleMaterial.builderOf(WHITE_SEETHROUGH_MATERIAL)
            .depthTest(DepthTest.LEQUAL)
            .build();
    private static final Material WHITE_SOLID_MATERIAL = SimpleMaterial.builderOf(WHITE_SNEAK_MATERIAL)
            .writeMask(WriteMask.COLOR_DEPTH)
            .build();
    private static final Model WHITE_SEETHROUGH_MODEL = new SingleMeshModel(GlyphMesh.INSTANCE, WHITE_SEETHROUGH_MATERIAL);
    private static final Model WHITE_SNEAK_MODEL = new SingleMeshModel(GlyphMesh.INSTANCE, WHITE_SNEAK_MATERIAL);
    private static final Model WHITE_SOLID_MODEL = new SingleMeshModel(GlyphMesh.INSTANCE, WHITE_SOLID_MATERIAL);

    private static final RendererReloadCache<ResourceLocation, Model> FONT_SEETHROUGH_MODELS =
            new RendererReloadCache<>(texture -> new SingleMeshModel(GlyphMesh.INSTANCE, fontMaterial(texture, false)));
    private static final RendererReloadCache<ResourceLocation, Model> FONT_SOLID_MODELS =
            new RendererReloadCache<>(texture -> new SingleMeshModel(GlyphMesh.INSTANCE, fontMaterial(texture, true)));

    private final VisualizationContext context;
    private final Random obfuscatedRandom = new Random();
    private final ArrayList<GlyphInstance> building = new ArrayList<>();

    private GlyphInstance[] instances = EMPTY;
    @Nullable
    private String lastText;
    private boolean lastSneaking;
    private boolean lastUnicode;
    private boolean hasObfuscated;

    public NameTagComponent(VisualizationContext context) {
        this.context = context;
    }

    public void beginFrame(String text, boolean sneaking, float anchorX, float anchorY, float anchorZ, int packedLight) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        boolean unicode = font.getUnicodeFlag();
        if (hasObfuscated || sneaking != lastSneaking || unicode != lastUnicode || !text.equals(lastText)) {
            lastText = text;
            lastSneaking = sneaking;
            lastUnicode = unicode;
            layout(font, text, sneaking, unicode);
        }
        for (GlyphInstance glyph : instances) {
            glyph.anchor(anchorX, anchorY, anchorZ).light(packedLight).setChanged();
        }
    }

    private void layout(FontRenderer font, String text, boolean sneaking, boolean unicode) {
        deleteInstances();
        hasObfuscated = false;
        int half = font.getStringWidth(text) / 2;
        int shift = "deadmau5".equals(text) ? -10 : 0;
        rect(sneaking ? WHITE_SNEAK_MODEL : WHITE_SEETHROUGH_MODEL,
                -half - 1, -1 + shift, half + 1, 8 + shift, 0, 63);
        if (sneaking) {
            emitText(font, text, unicode, -half, shift, 0x20, true);
        } else {
            emitText(font, text, unicode, -half, shift, 0x20, false);
            emitText(font, text, unicode, -half, shift, 0xFF, true);
        }
        instances = building.toArray(EMPTY);
        building.clear();
    }

    // Mirrors FontRenderer.renderStringAtPos (no drop shadow: nameplates never draw one).
    private void emitText(FontRenderer font, String text, boolean unicode, float startX, int shift,
                          int alpha, boolean solid) {
        float posX = startX;
        int rgb = 0xFFFFFF;
        boolean random = false;
        boolean bold = false;
        boolean strike = false;
        boolean underline = false;
        boolean italic = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 167 && i + 1 < text.length()) {
                int code = FORMATTING_CODES.indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (code < 16) {
                    random = bold = strike = underline = italic = false;
                    rgb = font.getColorCode(FORMATTING_CODES.charAt(code < 0 ? 15 : code));
                } else if (code == 16) {
                    random = true;
                } else if (code == 17) {
                    bold = true;
                } else if (code == 18) {
                    strike = true;
                } else if (code == 19) {
                    underline = true;
                } else if (code == 20) {
                    italic = true;
                } else {
                    random = bold = strike = underline = italic = false;
                    rgb = 0xFFFFFF;
                }
                i++;
                continue;
            }
            int tableIndex = ASCII_TABLE.indexOf(c);
            if (random && tableIndex != -1) {
                hasObfuscated = true;
                int width = font.getCharWidth(c);
                char swap;
                do {
                    tableIndex = obfuscatedRandom.nextInt(ASCII_TABLE.length());
                    swap = ASCII_TABLE.charAt(tableIndex);
                } while (width != font.getCharWidth(swap));
                c = swap;
            }
            float boldOffset = tableIndex == -1 || unicode ? 0.5F : 1.0F;
            float advance = glyph(font, c, tableIndex, unicode, italic, posX, shift, rgb, alpha, solid);
            if (bold) {
                glyph(font, c, tableIndex, unicode, italic, posX + boldOffset, shift, rgb, alpha, solid);
                advance++;
            }
            if (strike) {
                rect(solid ? WHITE_SOLID_MODEL : WHITE_SEETHROUGH_MODEL,
                        posX, shift + 3, posX + advance, shift + 4, rgb, alpha);
            }
            if (underline) {
                rect(solid ? WHITE_SOLID_MODEL : WHITE_SEETHROUGH_MODEL,
                        posX - 1, shift + 8, posX + advance, shift + 9, rgb, alpha);
            }
            posX += (int) advance;
        }
    }

    /** Emits one glyph quad (none for spaces) and returns the advance, both per vanilla. */
    private float glyph(FontRenderer font, char c, int tableIndex, boolean unicode, boolean italic,
                        float x, int shift, int rgb, int alpha, boolean solid) {
        if (c == ' ' || c == 160) {
            return 4.0F;
        }
        float shear = italic ? 1.0F : 0.0F;
        if (tableIndex != -1 && !unicode) {
            // renderDefaultChar: an (advance - 1.01)-wide, 7.99-tall quad from the 8px ascii grid.
            int advance = font.getCharWidth(c);
            float f = advance - 0.01F;
            GlyphInstance glyph = emit(fontModel(ASCII_TEXTURE, solid));
            glyph.placement(x, shift, f - 1.0F, 7.99F, shear);
            glyph.uvRegion(tableIndex % 16 * 8 / 128.0F, tableIndex / 16 * 8 / 128.0F,
                    (f - 1.0F) / 128.0F, 7.99F / 128.0F);
            color(glyph, rgb, alpha);
            return advance;
        }
        // renderUnicodeChar: glyphWidth packs the 16px cell's start/end columns in nibbles;
        // the quad renders at half scale.
        int packed = font.glyphWidth[c] & 255;
        if (packed == 0) {
            return 0.0F;
        }
        int start = packed >>> 4;
        int end = packed & 15;
        float width = end + 1 - start - 0.02F;
        GlyphInstance glyph = emit(fontModel(unicodePage(c / 256), solid));
        glyph.placement(x, shift, width / 2.0F, 7.99F, shear);
        glyph.uvRegion((c % 16 * 16 + start) / 256.0F, (c & 255) / 16 * 16 / 256.0F,
                width / 256.0F, 15.98F / 256.0F);
        color(glyph, rgb, alpha);
        return (end + 1 - start) / 2.0F + 1.0F;
    }

    private void rect(Model model, float x0, float y0, float x1, float y1, int rgb, int alpha) {
        GlyphInstance glyph = emit(model);
        glyph.placement(x0, y0, x1 - x0, y1 - y0, 0.0F);
        glyph.uvRegion(0.0F, 0.0F, 1.0F, 1.0F);
        color(glyph, rgb, alpha);
    }

    private GlyphInstance emit(Model model) {
        GlyphInstance glyph = context.instancerProvider().instancer(InstanceTypes.GLYPH, model)
                .createInstance();
        building.add(glyph);
        return glyph;
    }

    private static void color(GlyphInstance glyph, int rgb, int alpha) {
        glyph.color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
    }

    private static Model fontModel(ResourceLocation texture, boolean solid) {
        return (solid ? FONT_SOLID_MODELS : FONT_SEETHROUGH_MODELS).get(texture);
    }

    private static ResourceLocation unicodePage(int page) {
        ResourceLocation location = UNICODE_PAGES[page];
        if (location == null) {
            // Benign worker-thread race: idempotent value.
            location = new ResourceLocation(String.format("textures/font/unicode_page_%02x.png", page));
            UNICODE_PAGES[page] = location;
        }
        return location;
    }

    private static Material fontMaterial(ResourceLocation texture, boolean solid) {
        return SimpleMaterial.builder()
                .texture(texture)
                .transparency(Transparency.TRANSLUCENT)
                .depthTest(solid ? DepthTest.LEQUAL : DepthTest.OFF)
                .writeMask(solid ? WriteMask.COLOR_DEPTH : WriteMask.COLOR)
                .cardinalLightingMode(CardinalLightingMode.OFF)
                .useOverlay(false)
                .mipmap(false)
                .build();
    }

    private void deleteInstances() {
        for (GlyphInstance glyph : instances) {
            glyph.delete();
        }
        instances = EMPTY;
    }

    public void delete() {
        deleteInstances();
        lastText = null;
    }

    private static final class GlyphMesh implements QuadMesh {
        private static final GlyphMesh INSTANCE = new GlyphMesh();
        private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0, 0, 0, 1);

        @Override
        public int vertexCount() {
            return 4;
        }

        @Override
        public void write(MutableVertexList vertexList) {
            // Unit quad in font space (y down); the instance shader places and scales it.
            writeVertex(vertexList, 0, 0, 0);
            writeVertex(vertexList, 1, 0, 1);
            writeVertex(vertexList, 2, 1, 1);
            writeVertex(vertexList, 3, 1, 0);
        }

        private static void writeVertex(MutableVertexList v, int i, float x, float y) {
            v.x(i, x);
            v.y(i, y);
            v.z(i, 0);
            v.r(i, 1);
            v.g(i, 1);
            v.b(i, 1);
            v.a(i, 1);
            v.u(i, x);
            v.v(i, y);
            v.light(i, 0);
            v.overlay(i, OverlayTexture.NO_OVERLAY);
            v.normalX(i, 0);
            v.normalY(i, 1);
            v.normalZ(i, 0);
        }

        @Override
        public Vector4fc boundingSphere() {
            return BOUNDING_SPHERE;
        }
    }
}
