package dev.engine_room.flywheel.impl.event;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public record RenderContextImpl(LevelRenderer renderer, ClientLevel level, RenderBuffers buffers, Matrix4fc modelView,
                                Matrix4fc projection, Matrix4fc viewProjection, Camera camera,
                                float partialTick) implements RenderContext {
    public static RenderContextImpl create(LevelRenderer renderer, ClientLevel level, RenderBuffers buffers,
                                           Matrix4fc modelView, Matrix4f projection, Camera camera, float partialTick) {
        Matrix4f viewProjection = new Matrix4f(projection);
        viewProjection.mul(modelView);

        return new RenderContextImpl(renderer, level, buffers, modelView, projection, viewProjection, camera,
                partialTick);
    }

    public static RenderContextImpl captureCurrent(LevelRenderer renderer, ClientLevel level,
                                                   Matrix4fc modelViewMatrix) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        Matrix4f modelView = new Matrix4f(modelViewMatrix);
        // 26.2 exposes no standalone projection matrix (stored as a GpuBufferSlice); reconstruct it from the
        // camera's combined view-rotation-projection and the (rotation-only) modelView.
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Matrix4f projection = new Matrix4f(viewProjection).mul(new Matrix4f(modelView).invert());
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return create(renderer, level, minecraft.gameRenderer.renderBuffers(), modelView, projection, camera,
                partialTick);
    }
}
