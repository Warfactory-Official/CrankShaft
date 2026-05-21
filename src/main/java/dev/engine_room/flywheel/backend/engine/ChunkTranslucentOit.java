package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.compile.ChunkOitPrograms;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * Replays each visible vanilla translucent chunk's VBO through {@link ChunkOitPrograms} so chunk
 * geometry interleaves by depth with flywheel-OIT instance draws in the same composite.
 */
public final class ChunkTranslucentOit {
    private static final int TRANSLUCENT_LAYER_ORDINAL = BlockRenderLayer.TRANSLUCENT.ordinal();
    private static final int STRIDE = 28;

    // 7 == GL_QUADS — vanilla VertexBuffer.drawArrays(7).
    private static final int CHUNK_PRIMITIVE = GL11.GL_QUADS;

    // layout(location=N) in chunk_oit.vert.
    private static final int ATTR_POS = 0;
    private static final int ATTR_COLOR = 1;
    private static final int ATTR_UV0 = 2;
    private static final int ATTR_UV1 = 3;

    // core/compat 150+ requires a non-zero VAO bound for glVertexAttribPointer to take effect.
    // Lazily allocated; each replay rebinds VAO + per-chunk VBO and re-issues the pointer calls.
    private static int vao = 0;

    private ChunkTranslucentOit() {
    }

    /** True when there is at least one visible translucent chunk to route through OIT this frame. */
    public static boolean hasVisibleTranslucentChunks() {
        RenderGlobal rg = Minecraft.getMinecraft().renderGlobal;
        if (rg == null) {
            return false;
        }
        var container = rg.renderContainer;
        if (container == null) {
            return false;
        }
        return !container.renderChunks.isEmpty();
    }

    /** Replay every visible translucent chunk VBO into the given OIT-phase program. */
    public static void replay(ChunkOitPrograms programs, PipelineCompiler.OitMode mode) {
        RenderGlobal rg = Minecraft.getMinecraft().renderGlobal;
        if (rg == null) {
            return;
        }
        var container = rg.renderContainer;
        if (container == null) {
            return;
        }
        List<RenderChunk> chunks = container.renderChunks;
        if (chunks.isEmpty()) {
            // Mixin orchestration must ensure replay fires AFTER the filter loop at HEAD of
            // public renderBlockLayer(TRANSLUCENT) — otherwise this list is empty.
            return;
        }

        GlProgram program = programs.get(mode);
        if (program == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        // Mirror what the (mixin-suppressed) vanilla private renderBlockLayer does: rebind block
        // atlas to T0 and call enableLightmap() so T1 has the lightmap texture + matrix set up.
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        mc.entityRenderer.enableLightmap();

        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
        }
        GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(ATTR_POS);
        GL20.glEnableVertexAttribArray(ATTR_COLOR);
        GL20.glEnableVertexAttribArray(ATTR_UV0);
        GL20.glEnableVertexAttribArray(ATTR_UV1);

        program.bind();

        for (int i = 0, n = chunks.size(); i < n; i++) {
            RenderChunk renderChunk = chunks.get(i);
            VertexBuffer vbo = renderChunk.getVertexBufferByLayer(TRANSLUCENT_LAYER_ORDINAL);

            GlStateManager.pushMatrix();
            container.preRenderChunk(renderChunk);
            renderChunk.multModelviewMatrix();

            vbo.bindBuffer();
            // Re-issued per chunk: glVertexAttribPointer captures the currently-bound ARRAY_BUFFER.
            // Lightmap is NOT normalized — raw SHORT pixel coords feed gl_TextureMatrix[1].
            GL20.glVertexAttribPointer(ATTR_POS, 3, GL11.GL_FLOAT, false, STRIDE, 0);
            GL20.glVertexAttribPointer(ATTR_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 12);
            GL20.glVertexAttribPointer(ATTR_UV0, 2, GL11.GL_FLOAT, false, STRIDE, 16);
            GL20.glVertexAttribPointer(ATTR_UV1, 2, GL11.GL_SHORT, false, STRIDE, 24);
            vbo.drawArrays(CHUNK_PRIMITIVE);

            GlStateManager.popMatrix();
        }

        GL20.glDisableVertexAttribArray(ATTR_POS);
        GL20.glDisableVertexAttribArray(ATTR_COLOR);
        GL20.glDisableVertexAttribArray(ATTR_UV0);
        GL20.glDisableVertexAttribArray(ATTR_UV1);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        mc.entityRenderer.disableLightmap();

        // enableLightmap() bound the lightmap onto lightmapTexUnit (== Samplers.OVERLAY, T1),
        // clobbering the overlay TextureBinder put there; disableLightmap() only toggles the
        // fixed-function enable bit, leaving the lightmap texture name bound. Re-establish the
        // overlay (T1) + light (T2) bindings so the caller's next instance draws sample
        // flw_overlayTex correctly. Covers every replay phase and call site.
        TextureBinder.bindLightAndOverlay();

        // Caller's next phase rebinds its own program — skip the unbind to save state churn.
    }

    /** Free the lazily-allocated replay VAO; idempotent and lets a later replay regenerate it. */
    public static void deleteVao() {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
