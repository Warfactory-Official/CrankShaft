package dev.engine_room.flywheel.iris.experimental.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.iris.experimental.CompositeIndirectDraw;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.GraphicsPipeline;
import net.irisshaders.iris.backend.SyncCommand;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.pipeline.programs.CompositeProgram;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(CompositeProgram.class)
abstract class ExperimentalCompositeProgramMixin implements CompositeIndirectDraw {
    @Unique
    private final Vector2i flywheel$viewport = new Vector2i();
    @Shadow
    private String name;
    @Shadow
    private GraphicsPipeline pipeline;
    @Shadow
    private GpuBuffer vertices;
    @Shadow
    private GpuBuffer quadIndices;

    @Override
    public void flywheel$drawIndirect(List<GpuTextureView> targets, ViewportData viewport, GpuBufferSlice command,
                                      long tick, long frame) {
        var program = (CompositeProgram) (Object) this;
        program.prepare(targets);
        program.getUniforms().update(tick, frame);
        var backend = Iris.getRenderBackend();
        var sync = backend.createSync().read(pipeline).read(command, SyncCommand.Access.INDIRECT);
        for (int i = 0; i < targets.size(); i++) sync.write(targets.get(i), SyncCommand.Access.COLOR_ATTACHMENT);
        sync.nextIs(SyncCommand.Stage.GRAPHICS).run();
        int width = targets.getFirst().getWidth(0), height = targets.getFirst().getHeight(0);
        try (var pass = backend.createRenderPass(name, pipeline.framebuffer(), targets, null,
                flywheel$viewport.set(width, height))) {
            pass.setViewport((int) (viewport.viewportX() * width), (int) (viewport.viewportY() * height),
                    (int) (width * viewport.scale()), (int) (height * viewport.scale()));
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(quadIndices, IndexType.SHORT);
            pass.bindPipeline(pipeline);
            pass.drawIndexedIndirect(command, 1);
        }
    }
}
