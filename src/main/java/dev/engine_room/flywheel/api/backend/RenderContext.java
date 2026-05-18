package dev.engine_room.flywheel.api.backend;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import org.joml.Matrix4fc;

public interface RenderContext {
    RenderGlobal renderer();

    WorldClient level();

    Matrix4fc modelView();

    Matrix4fc projection();

    Matrix4fc viewProjection();

    Camera camera();

    float partialTick();
}
