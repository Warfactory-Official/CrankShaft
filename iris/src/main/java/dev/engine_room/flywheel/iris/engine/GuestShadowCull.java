package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.component.UberCullComponent;
import dev.engine_room.flywheel.backend.compile.core.CompilationHarness;
import dev.engine_room.flywheel.backend.compile.core.Compile;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import dev.engine_room.flywheel.iris.mixin.ShadowFrustumAccessors;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.CullEverythingFrustum;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.irisshaders.iris.shadows.frustum.advanced.SafeZoneCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * GPU replica of Iris's shadow frustum ({@link ShadowRenderer#FRUSTUM}) for the indirect guest's pass-2 cull.
 */
final class GuestShadowCull {
    private static final int BINDING = 13;
    private static final int MAX_PLANES = 13;
    // std140 _FlwShadowCull: vec4[13], vec3 + int, float, float.
    private static final int SIZE = 16 * MAX_PLANES + 24;

    private static final Compile<InstanceTypeIds.Snapshot> COMPILE = new Compile<>();

    private final CompilationHarness<InstanceTypeIds.Snapshot> programs = COMPILE.program()
            .link(COMPILE.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.COMPUTE)
                         .nameMapper(snapshot -> "iris/shadow_cull_uber" + snapshot.types().size())
                         .requireExtensions(List.of())
                         .withResource(ResourceUtil.rl("internal/indirect/cull_api_impl.glsl"))
                         .with((snapshot, sources) -> new UberCullComponent(snapshot.types(), sources))
                         .withResource(ResourceUtil.rl("iris/shadow_cull.glsl")))
            .postLink((key, program) -> {
                Uniforms.setUniformBlockBindings(program);
                program.setUniformBlockBinding("_FlwShadowCull", BINDING);
            })
            .harness("iris_shadow_cull", FlwPrograms.SOURCES);
    private final GlBuffer uniform = new GlBuffer(GlBufferUsage.DYNAMIC_DRAW);
    private final MemoryBlock block = MemoryBlock.calloc(SIZE, 1);

    /**
     * Uploads this frame's frustum and returns the cull program, or {@code null} when Iris culls everything.
     */
    @Nullable GlProgram prepare(Vec3i renderOrigin, Vec3 camera) {
        Frustum frustum = ShadowRenderer.FRUSTUM;
        if (frustum instanceof CullEverythingFrustum) {
            return null;
        }

        long ptr = block.ptr();
        MemoryUtil.memSet(ptr, 0, SIZE);
        int planeCount = 0;
        double distance = 0;
        double safeZone = 0;
        if (frustum instanceof AdvancedShadowCullingFrustum) {
            var advanced = (ShadowFrustumAccessors.Advanced) frustum;
            float[][] planes = advanced.flywheel$planes();
            planeCount = advanced.flywheel$planeCount();
            for (int i = 0; i < planeCount; i++) {
                for (int c = 0; c < 4; c++) {
                    MemoryUtil.memPutFloat(ptr + 16L * i + 4L * c, planes[i][c]);
                }
            }
            if (frustum instanceof SafeZoneCullingFrustum) {
                distance = maxDistance(((ShadowFrustumAccessors.SafeZone) frustum).flywheel$distanceCuller());
                safeZone = maxDistance(advanced.flywheel$boxCuller());
            } else {
                distance = maxDistance(advanced.flywheel$boxCuller());
            }
        } else if (frustum instanceof BoxCullingFrustum) {
            distance = maxDistance(((ShadowFrustumAccessors.Box) frustum).flywheel$boxCuller());
        }

        long tail = ptr + 16L * MAX_PLANES;
        MemoryUtil.memPutFloat(tail, (float) (renderOrigin.getX() - camera.x));
        MemoryUtil.memPutFloat(tail + 4, (float) (renderOrigin.getY() - camera.y));
        MemoryUtil.memPutFloat(tail + 8, (float) (renderOrigin.getZ() - camera.z));
        MemoryUtil.memPutInt(tail + 12, planeCount);
        MemoryUtil.memPutFloat(tail + 16, (float) distance);
        MemoryUtil.memPutFloat(tail + 20, (float) safeZone);
        uniform.upload(block);
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING, uniform.handle());

        return programs.get(InstanceTypeIds.snapshot());
    }

    private static double maxDistance(@Nullable BoxCuller culler) {
        return culler == null ? 0 : ((ShadowFrustumAccessors.Culler) culler).flywheel$maxDistance();
    }

    void delete() {
        programs.delete();
        uniform.delete();
        block.free();
    }
}
