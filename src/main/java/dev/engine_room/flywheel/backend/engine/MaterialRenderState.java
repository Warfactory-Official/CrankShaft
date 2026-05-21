package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.Comparator;

public final class MaterialRenderState {
    public static final Comparator<Material> COMPARATOR = MaterialRenderState::compare;

    private MaterialRenderState() {
    }

    public static void setup(Material material) {
        setupTexture(material);
        setupBackfaceCulling(material.backfaceCulling());
        setupPolygonOffset(material.polygonOffset());
        setupDepthTest(material.depthTest());
        setupTransparency(material.transparency());
        setupWriteMask(material.writeMask());
    }

    public static void setupOit(Material material) {
        setupTexture(material);
        setupBackfaceCulling(material.backfaceCulling());
        setupPolygonOffset(material.polygonOffset());
        setupDepthTest(material.depthTest());

        WriteMask mask = material.writeMask();
        boolean writeColor = mask.color();
        GlStateManager.colorMask(writeColor, writeColor, writeColor, writeColor);
    }

    private static void setupTexture(Material material) {
        GlTextureUnit.T0.makeActive();
        // 1.12.2: TextureManager.getTexture(rl) returns null for textures not yet loaded.
        // bindTexture(rl) loads + caches + binds in one call. After it runs, getTexture is
        // guaranteed non-null, which is the path we need to set blur/mipmap.
        TextureManager mgr = Minecraft.getMinecraft().getTextureManager();
        mgr.bindTexture(material.texture());
        ITextureObject texture = mgr.getTexture(material.texture());
        if (texture instanceof AbstractTexture abs) {
            abs.setBlurMipmap(material.blur(), material.mipmap());
        }
    }

    private static void setupBackfaceCulling(boolean backfaceCulling) {
        if (backfaceCulling) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
    }

    private static void setupPolygonOffset(boolean polygonOffset) {
        if (polygonOffset) {
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
            GlStateManager.enablePolygonOffset();
        } else {
            GlStateManager.doPolygonOffset(0.0F, 0.0F);
            GlStateManager.disablePolygonOffset();
        }
    }

    private static void setupDepthTest(DepthTest depthTest) {
        switch (depthTest) {
        case OFF -> GlStateManager.disableDepth();
        case NEVER -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_NEVER);
        }
        case LESS -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LESS);
        }
        case EQUAL -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_EQUAL);
        }
        case LEQUAL -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
        }
        case GREATER -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_GREATER);
        }
        case NOTEQUAL -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_NOTEQUAL);
        }
        case GEQUAL -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_GEQUAL);
        }
        case ALWAYS -> {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_ALWAYS);
        }
        }
    }

    private static void setupTransparency(Transparency transparency) {
        switch (transparency) {
        case OPAQUE -> GlStateManager.disableBlend();
        case ADDITIVE -> {
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        }
        case LIGHTNING -> {
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        }
        case GLINT -> {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_COLOR, GlStateManager.DestFactor.ONE, GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        }
        case CRUMBLING -> {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_COLOR, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        }
        case TRANSLUCENT, ORDER_INDEPENDENT -> {
            // ORDER_INDEPENDENT merges with TRANSLUCENT here for the non-OIT consumers (OF backend,
            // stock without OIT). The OIT pipeline drives via setupOit() and never reaches this case.
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        }
        }
    }

    private static void setupWriteMask(WriteMask mask) {
        GlStateManager.depthMask(mask.depth());
        boolean writeColor = mask.color();
        GlStateManager.colorMask(writeColor, writeColor, writeColor, writeColor);
    }

    public static void reset() {
        resetTexture();
        resetBackfaceCulling();
        resetPolygonOffset();
        resetDepthTest();
        resetTransparency();
        resetWriteMask();
    }

    private static void resetTexture() {
        GlTextureUnit.T0.makeActive();
        GlStateManager.bindTexture(0);
    }

    private static void resetBackfaceCulling() {
        GlStateManager.enableCull();
    }

    private static void resetPolygonOffset() {
        GlStateManager.doPolygonOffset(0.0F, 0.0F);
        GlStateManager.disablePolygonOffset();
    }

    private static void resetDepthTest() {
        // Vanilla 1.12.2 keeps GL_DEPTH_TEST enabled throughout the world-render frame
        // (water/entities/post-render code all rely on it). Upstream Flywheel can disable
        // here because Mojang's blaze3d state manager re-enables before each draw, but
        // 1.12.2 has no such layer — leaving depth test off lets water render in front of
        // opaque terrain. Restore to vanilla's expected state instead.
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
    }

    private static void resetTransparency() {
        GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
    }

    private static void resetWriteMask() {
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
    }

    public static boolean materialEquals(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return true;
        }

        // Not here because ubershader: useLight, useOverlay, diffuse, fog shader, ambient occlusion
        // Everything in the comparator should be here.
        return lhs.blur() == rhs.blur()
                && lhs.mipmap() == rhs.mipmap()
                && lhs.backfaceCulling() == rhs.backfaceCulling()
                && lhs.polygonOffset() == rhs.polygonOffset()
                && lhs.depthTest() == rhs.depthTest()
                && lhs.transparency() == rhs.transparency()
                && lhs.writeMask() == rhs.writeMask()
                && lhs.light().source().equals(rhs.light().source())
                && lhs.texture().equals(rhs.texture())
                && lhs.cutout().source().equals(rhs.cutout().source())
                && lhs.shaders().fragmentSource().equals(rhs.shaders().fragmentSource())
                && lhs.shaders().vertexSource().equals(rhs.shaders().vertexSource());
    }

    public static boolean materialIsAllNonNull(@Nullable Material material) {
        // We do not trust people to give us valid NotNull objects.
        return material != null &&
                material.shaders() != null &&
                material.shaders().fragmentSource() != null &&
                material.shaders().vertexSource() != null &&
                material.fog() != null &&
                material.fog().source() != null &&
                material.cutout() != null &&
                material.cutout().source() != null &&
                material.light() != null &&
                material.light().source() != null &&
                material.texture() != null &&
                material.depthTest() != null &&
                material.transparency() != null &&
                material.writeMask() != null &&
                material.cardinalLightingMode() != null;
    }

    public static int compare(Material lhs, Material rhs) {
        if (lhs == rhs) {
            return 0;
        }

        int cmp;
        cmp = lhs.transparency().compareTo(rhs.transparency());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.light().source().compareTo(rhs.light().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.cutout().source().compareTo(rhs.cutout().source());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().fragmentSource().compareTo(rhs.shaders().fragmentSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.shaders().vertexSource().compareTo(rhs.shaders().vertexSource());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.texture().compareTo(rhs.texture());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.blur(), rhs.blur());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.mipmap(), rhs.mipmap());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.backfaceCulling(), rhs.backfaceCulling());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Boolean.compare(lhs.polygonOffset(), rhs.polygonOffset());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.depthTest().compareTo(rhs.depthTest());
        if (cmp != 0) {
            return cmp;
        }
        cmp = lhs.writeMask().compareTo(rhs.writeMask());
        return cmp;
    }
}
