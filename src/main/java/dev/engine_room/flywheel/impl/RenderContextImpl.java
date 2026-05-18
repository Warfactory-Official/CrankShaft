package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.backend.Camera;
import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

public record RenderContextImpl(RenderGlobal renderer,
                                WorldClient level,
                                Matrix4fc modelView,
                                Matrix4fc projection,
                                Matrix4fc viewProjection,
                                Camera camera,
                                float partialTick) implements RenderContext {
    public static RenderContext create(RenderGlobal renderer, WorldClient level,
                                       Entity viewEntity, float partialTicks) {
        // 1.12.2: ActiveRenderInfo's cached matrices are refreshed just before our setupTerrain
        // inject, so we read them instead of glGetFloatv. Undo the eye-height translation baked
        // into GL_MODELVIEW to get a world-space VIEW.
        Matrix4f modelView = new Matrix4f().set(ActiveRenderInfo.MODELVIEW);
        modelView.translate(0f, viewEntity.getEyeHeight(), 0f);
        Matrix4f projection = new Matrix4f().set(ActiveRenderInfo.PROJECTION);

        // 1.12.2: eyePosition feeds VIEW.translate(-eye) in FrameUniforms — must equal the player
        // eye, not the third-person back-off camera, or every BE/Entity shifts. viewpoint is the
        // actual rendered camera position (derived from modelView⁻¹); used for frustum culling,
        // distance sorting, and flw_cameraPos so fog distance matches vanilla.
        Vec3d eyePosition = viewEntity.getPositionEyes(partialTicks);
        Vector3f eyeRelative = new Matrix4f(modelView).invert().transformPosition(new Vector3f());
        Vec3d viewpoint = new Vec3d(
                eyePosition.x + eyeRelative.x,
                eyePosition.y + eyeRelative.y,
                eyePosition.z + eyeRelative.z);

        return new RenderContextImpl(renderer, level, modelView, projection,
                new Matrix4f(projection).mul(modelView),
                new CameraImpl(viewEntity, partialTicks, viewpoint, eyePosition), partialTicks);
    }
}
