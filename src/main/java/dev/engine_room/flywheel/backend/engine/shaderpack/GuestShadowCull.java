package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.lwjgl.opengl.GL20;

final class GuestShadowCull {
    static final int MAX_PLANES = 13;
    private final float[] planes = new float[MAX_PLANES * 4];
    private int planeCount;
    private float distance;
    private float x, y, z;

    void prepare(GuestRenderPass pass) {
        planeCount = pass.host.shadowPlanes(planes);
        distance = pass.host.shadowDistance();
        var offset = pass.originToCamera();
        x = offset.x;
        y = offset.y;
        z = offset.z;
    }

    void upload(GlProgram program) {
        program.bind();
        program.setInt("_flw_shadowPlaneCount", planeCount);
        program.setFloat("_flw_shadowDistance", distance);
        program.setVec3("_flw_shadowOriginOffset", x, y, z);
        if (planeCount > 0) GL20.glUniform4fv(program.getUniformLocation("_flw_shadowPlanes[0]"), planes);
    }
}
