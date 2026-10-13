package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.SyncCommand;

import java.util.List;
import java.util.Map;

final class ExperimentalReplayTextures {
    private ExperimentalReplayTextures() {
    }

    static GpuTextureView snapshot(GpuTextureView old, GpuTextureView source, ExperimentalReplayProgram depthCopy,
                                   GpuBuffer indices, GpuBufferSlice command) {
        old = ensure(old, source);
        if (source.texture().getFormat().hasDepthAspect())
            depthCopy.draw(List.of(), old, Map.of("depth", source), indices, command, 0);
        else copy(source, old);
        return old;
    }

    static GpuTextureView ensure(GpuTextureView old, GpuTextureView source) {
        if (old == null || old.getWidth(0) != source.getWidth(0) || old.getHeight(0) != source.getHeight(0)
                || old.texture().getFormat() != source.texture().getFormat()) {
            if (old != null) retire(old);
            var texture = RenderSystem.getDevice().createTexture("flywheel material replay snapshot",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC
                            | GpuTexture.USAGE_COPY_DST, source.texture().getFormat(), source.getWidth(0),
                    source.getHeight(0), 1, 1);
            old = RenderSystem.getDevice().createTextureView(texture);
        }
        return old;
    }

    static void copy(GpuTextureView source, GpuTextureView destination) {
        Iris.getRenderBackend().createSync().read(source, SyncCommand.Access.TRANSFER)
            .write(destination, SyncCommand.Access.TRANSFER).nextIs(SyncCommand.Stage.TRANSFER).run();
        Iris.getRenderBackend().commands()
            .copyTexture(source.texture(), destination.texture(), source.getWidth(0), source.getHeight(0));
    }

    static void retire(GpuTextureView view) {
        var texture = view.texture();
        Iris.getRenderBackend().retire(() -> {
            view.close();
            texture.close();
        });
    }
}
