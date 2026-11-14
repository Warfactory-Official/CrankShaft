package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.iris.experimental.GlClearSequence;
import net.irisshaders.iris.opengl.GlRenderBackend;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(GlRenderBackend.class)
abstract class ExperimentalGlClearSequenceMixin {
    @WrapMethod(method = "clearColors")
    private void flywheel$clearSequence(List<GpuTextureView> textures, Vector4f color, Operation<Void> original) {
        if (textures.size() < 2 || GlClearSequence.active) {
            original.call(textures, color);
            return;
        }
        GlClearSequence.active = true;
        GlClearSequence.pendingUnbind = false;
        try {
            original.call(textures, color);
        } finally {
            // If validation failed before any pass closed, preserve the caller's original bindings.
            GlClearSequence.active = false;
            if (GlClearSequence.pendingUnbind) {
                GlClearSequence.pendingUnbind = false;
                GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            }
        }
    }
}
