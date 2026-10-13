package dev.engine_room.flywheel.iris.experimental.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.iris.experimental.IndirectCommands;
import net.irisshaders.iris.opengl.GlGraphicsShader;
import net.irisshaders.iris.opengl.GlWorldCommands;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GlWorldCommands.class)
abstract class ExperimentalGlIndirectCountMixin implements IndirectCommands {
    @Shadow
    private GlGraphicsShader.Pipeline pipeline;

    @Shadow
    protected abstract void prepareDraw();

    @Shadow
    protected abstract int getGlIndexType();

    @Shadow
    protected abstract void applyStorageBarrier();

    @Override
    public void flywheel$drawIndexedIndirect(GpuBufferSlice commands, int count, int stride) {
        prepareDraw();
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, ((GlBuffer) commands.buffer()).handle());
        GL43C.glMultiDrawElementsIndirect(pipeline.getTopology(), getGlIndexType(), commands.offset(), count, stride);
        applyStorageBarrier();
    }

    @Override
    public void flywheel$drawIndexedIndirectCount(GpuBufferSlice commands, GpuBufferSlice count, int maximum) {
        prepareDraw();
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, ((GlBuffer) commands.buffer()).handle());
        GlStateManager._glBindBuffer(GL46C.GL_PARAMETER_BUFFER, ((GlBuffer) count.buffer()).handle());
        GlCompat.multiDrawElementsIndirectCount(pipeline.getTopology(), getGlIndexType(), commands.offset(),
                count.offset(), maximum, 20);
        applyStorageBarrier();
    }
}
