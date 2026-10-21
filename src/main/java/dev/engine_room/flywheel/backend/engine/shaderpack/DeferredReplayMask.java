package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

final class DeferredReplayMask implements AutoCloseable {
    private static final int[] ZERO = {0};
    private static final int[] FRONT = {GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_VALUE_MASK,
            GL11.GL_STENCIL_WRITEMASK, GL11.GL_STENCIL_FAIL, GL11.GL_STENCIL_PASS_DEPTH_FAIL, GL11.GL_STENCIL_PASS_DEPTH_PASS};
    private static final int[] BACK = {GL20.GL_STENCIL_BACK_FUNC, GL20.GL_STENCIL_BACK_REF, GL20.GL_STENCIL_BACK_VALUE_MASK,
            GL20.GL_STENCIL_BACK_WRITEMASK, GL20.GL_STENCIL_BACK_FAIL, GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL, GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS};
    private final DeferredReplayPlan.Storage abi;
    private final int radius;
    private final int[] stencilState = new int[14];
    private GlProgram tiles;
    private GlProgram mask;
    private int stencil;
    private int framebuffer;
    private int width, height;
    private int tileBuffer;
    private boolean stencilEnabled;

    DeferredReplayMask(DeferredReplayPlan.Storage abi, int radius) {
        this.abi = abi;
        this.radius = radius;
        try {
            tiles = DeferredReplayShaders.compute("layer_tiles.comp", this::defines);
            mask = DeferredReplayShaders.graphics("layer_mask.frag", this::defines);
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private void defines(Compilation compilation) {
        compilation.define("FLW_REPLAY_TILES", Integer.toString(abi.tilesBinding()));
        compilation.define("FLW_REPLAY_TILE_SHIFT", Integer.toString(abi.tileShift()));
        compilation.define("FLW_REPLAY_TILE_RADIUS", Integer.toString(radius));
    }

    void prepare(int width, int height, int tileBuffer) {
        var tileSize = 1 << abi.tileShift();
        var tilesX = (width + tileSize - 1) >> abi.tileShift();
        var tilesY = (height + tileSize - 1) >> abi.tileShift();
        if (this.width != width || this.height != height || this.tileBuffer != tileBuffer) {
            if (GL45C.glGetNamedBufferParameteri64(tileBuffer, GL15.GL_BUFFER_SIZE) < (long) tilesX * tilesY * 8)
                throw new IllegalStateException("Replay tile storage needs counts and dilation scratch");
            this.tileBuffer = tileBuffer;
            if (this.width != width || this.height != height) {
                release();
                this.width = width;
                this.height = height;
                stencil = GL45C.glCreateRenderbuffers();
                GL45C.glNamedRenderbufferStorage(stencil, GL30.GL_STENCIL_INDEX8, width, height);
                framebuffer = GL45C.glCreateFramebuffers();
                GL45C.glNamedFramebufferRenderbuffer(framebuffer, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, stencil);
                GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL11.GL_NONE);
                GL45C.glNamedFramebufferReadBuffer(framebuffer, GL11.GL_NONE);
                if (GL45C.glCheckNamedFramebufferStatus(framebuffer, GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Incomplete deferred replay tile mask");
            }
        }
        var previous = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, abi.tilesBinding());
        try (var state = GlStateTracker.getRestoreState()) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.tilesBinding(), tileBuffer);
            tiles.bind();
            GL20.glUniform2i(tiles.getUniformLocation("tileCount"), tilesX, tilesY);
            GL43.glDispatchCompute((tilesX + 7) / 8, (tilesY + 7) / 8, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            mask.bind();
            GL20.glUniform2i(mask.getUniformLocation("tileCount"), tilesX, tilesY);
            var front = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
            var back = GL11.glGetInteger(GL20.GL_STENCIL_BACK_WRITEMASK);
            var scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glStencilMask(0xFF);
            GL45C.glClearNamedFramebufferiv(framebuffer, GL11.GL_STENCIL, 0, ZERO);
            GL20.glStencilMaskSeparate(GL11.GL_FRONT, front);
            GL20.glStencilMaskSeparate(GL11.GL_BACK, back);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } finally { GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.tilesBinding(), previous); }
    }

    void layer(int layer, long command, DeferredReplayQuad quad) {
        var draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        var depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        var depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        var scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        var alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        var cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        var storage = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, abi.tilesBinding());
        saveStencil();
        try (var state = GlStateTracker.getRestoreState(); var stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL11.glViewport(0, 0, width, height);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                // 1.12.2: materialization can leave fixed-function alpha testing and face culling enabled.
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_STENCIL_TEST);
                GL11.glStencilMask(0xFF);
                GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
                GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.tilesBinding(), tileBuffer);
                mask.bind();
                mask.setInt("layerIndex", layer);
                quad.bind();
                GL40.glDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, command);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            }
        } finally {
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(depthWrite);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (alpha) GL11.glEnable(GL11.GL_ALPHA_TEST);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, abi.tilesBinding(), storage);
            restoreStencil();
        }
    }

    void draw(DeferredReplayPass pass, long command) {
        var draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try (var stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            if (draw == 0 || pass.width() != width || pass.height() != height || viewport.get(0) != 0
                    || viewport.get(1) != 0 || viewport.get(2) != width || viewport.get(3) != height)
                throw new IllegalStateException("Tile-masked replay needs a full offscreen viewport: " + pass.name());
        }
        var type = GL45C.glGetNamedFramebufferAttachmentParameteri(draw, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        var object = type == GL11.GL_NONE ? 0 : GL45C.glGetNamedFramebufferAttachmentParameteri(draw, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        var level = type == GL11.GL_TEXTURE ? GL45C.glGetNamedFramebufferAttachmentParameteri(draw, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) : 0;
        if (type == GL11.GL_TEXTURE && (GL45C.glGetNamedFramebufferAttachmentParameteri(draw, GL30.GL_STENCIL_ATTACHMENT, GL32.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0
                || GL45C.glGetNamedFramebufferAttachmentParameteri(draw, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER) != 0
                || GL45C.glGetNamedFramebufferAttachmentParameteri(draw, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE) != 0))
            throw new UnsupportedOperationException("Layered native stencil targets are outside the legacy replay ABI");
        saveStencil();
        try {
            GL45C.glNamedFramebufferRenderbuffer(draw, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, stencil);
            if (GL45C.glCheckNamedFramebufferStatus(draw, GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Native framebuffer cannot borrow replay stencil: " + pass.name());
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilMask(0);
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL40.glDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, command);
        } finally {
            // Legacy fullscreen attachments are non-layered 2D textures or renderbuffers.
            if (type == GL11.GL_TEXTURE) GL45C.glNamedFramebufferTexture(draw, GL30.GL_STENCIL_ATTACHMENT, object, level);
            else GL45C.glNamedFramebufferRenderbuffer(draw, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, object);
            restoreStencil();
        }
    }

    private void saveStencil() {
        stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        for (var i = 0; i < FRONT.length; i++) {
            stencilState[i] = GL11.glGetInteger(FRONT[i]);
            stencilState[i + 7] = GL11.glGetInteger(BACK[i]);
        }
    }

    private void restoreStencil() {
        if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
        for (var i = 0; i < 2; i++) {
            var face = i == 0 ? GL11.GL_FRONT : GL11.GL_BACK;
            var offset = i * 7;
            GL20.glStencilFuncSeparate(face, stencilState[offset], stencilState[offset + 1], stencilState[offset + 2]);
            GL20.glStencilMaskSeparate(face, stencilState[offset + 3]);
            GL20.glStencilOpSeparate(face, stencilState[offset + 4], stencilState[offset + 5], stencilState[offset + 6]);
        }
    }

    private void release() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (stencil != 0) GL30.glDeleteRenderbuffers(stencil);
        framebuffer = stencil = width = height = 0;
    }

    @Override public void close() {
        release();
        if (tiles != null) tiles.delete();
        if (mask != null) mask.delete();
    }
}
