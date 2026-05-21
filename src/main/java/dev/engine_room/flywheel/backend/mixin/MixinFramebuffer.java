package dev.engine_room.flywheel.backend.mixin;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * 1.12.2 workaround: swap vanilla {@code Framebuffer}'s depth attachment from a
 * {@code GL_RENDERBUFFER} to a {@code GL_TEXTURE_2D} so the HiZ cull pyramid can sample it.
 */
@Mixin(Framebuffer.class)
public abstract class MixinFramebuffer {

    // Forge-added field
    @Shadow(remap = false) private boolean stencilEnabled;

    @Redirect(method = "createFramebuffer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;glGenRenderbuffers()I"),
            require = 1)
    private int flw$genDepthTexture() {
        return GL11.glGenTextures();
    }

    @Redirect(method = "createFramebuffer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;glBindRenderbuffer(II)V"),
            require = 1)
    private void flw$bindDepthTexture(int target, int renderbuffer) {
        GlStateManager.bindTexture(renderbuffer);
    }

    @Redirect(method = "createFramebuffer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;glRenderbufferStorage(IIII)V"),
            require = 1)
    private void flw$allocDepthTexture(int target, int internalFormat, int width, int height) {
        int format;
        int type;
        if (internalFormat == GL30.GL_DEPTH24_STENCIL8) {
            format = GL30.GL_DEPTH_STENCIL;
            type = GL30.GL_UNSIGNED_INT_24_8;
        } else {
            format = GL11.GL_DEPTH_COMPONENT;
            type = GL11.GL_UNSIGNED_INT;
        }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (IntBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // Sampling the depth as a raw float (not a shadow compare) — required by HiZ which reads
        // the max linearized depth in a screen-space AABB and compares against a sphere-projected
        // depth bound. Shadow-compare mode would force a per-texel comparison instead of a value.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
    }

    @Redirect(method = "createFramebuffer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;glFramebufferRenderbuffer(IIII)V"),
            require = 1)
    private void flw$attachDepthTexture(int target, int attachment, int renderbufferTarget, int renderbuffer) {
        // Stencil attach is redundant once the depth attachment uses GL_DEPTH_STENCIL_ATTACHMENT.
        if (stencilEnabled && attachment == GL30.GL_STENCIL_ATTACHMENT) {
            return;
        }
        int finalAttachment = stencilEnabled ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : attachment;
        OpenGlHelper.glFramebufferTexture2D(target, finalAttachment, GL11.GL_TEXTURE_2D, renderbuffer, 0);
    }

    @Redirect(method = "deleteFramebuffer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;glDeleteRenderbuffers(I)V"),
            require = 1)
    private void flw$deleteDepthTexture(int renderbuffer) {
        GlStateManager.deleteTexture(renderbuffer);
    }
}
