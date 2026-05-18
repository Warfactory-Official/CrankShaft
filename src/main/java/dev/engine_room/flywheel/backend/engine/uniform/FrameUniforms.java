package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.api.backend.Camera;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.engine.indirect.DepthPyramid;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.function.IntSupplier;

public final class FrameUniforms extends UniformWriter {
    private static final int SIZE = 96 + 64 * 9 + 16 * 5 + 8 * 2 + 8 + 4 * 19;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.FRAME_INDEX, SIZE);

    private static final float Z_NEAR = 0.05f;

    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f VIEW_INVERSE = new Matrix4f();
    private static final Matrix4f VIEW_PREV = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f PROJECTION_INVERSE = new Matrix4f();
    private static final Matrix4f PROJECTION_PREV = new Matrix4f();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW_PROJECTION_INVERSE = new Matrix4f();
    private static final Matrix4f VIEW_PROJECTION_PREV = new Matrix4f();

    private static final Vector3f CAMERA_POS = new Vector3f();
    private static final Vector3f CAMERA_POS_PREV = new Vector3f();
    private static final Vector3f CAMERA_LOOK = new Vector3f();
    private static final Vector3f CAMERA_LOOK_PREV = new Vector3f();
    private static final Vector2f CAMERA_ROT = new Vector2f();
    private static final Vector2f CAMERA_ROT_PREV = new Vector2f();

    private static boolean firstWrite = true;

    private static int debugMode = DebugMode.OFF.ordinal();
    private static boolean frustumPaused = false;
    private static boolean frustumCapture = false;

    private static IntSupplier tickProvider = () -> 0;

    private FrameUniforms() {
    }

    public static void debugMode(DebugMode mode) {
        debugMode = mode.ordinal();
    }

    public static void captureFrustum() {
        frustumPaused = true;
        frustumCapture = true;
    }

    public static void unpauseFrustum() {
        frustumPaused = false;
    }

    public static void setTickProvider(IntSupplier supplier) {
        tickProvider = supplier;
    }

    public static float getDepthFar() {
        // 1.12.2: no GameRenderer#getDepthFar; decode zf from the captured perspective matrix:
        //   m22 = -(zf+zn)/(zf-zn),  m32 = -2*zf*zn/(zf-zn)  =>  zf = m32 / (m22 + 1)
        // Tracks mods that redirect the projection setup (CubicChunks vertical view distance,
        // etc.) automatically — the value here is exactly the far plane the shader renders against.
        // Valid after the first PROJECTION.set(context.projection()) in update().
        return PROJECTION.m32() / (PROJECTION.m22() + 1f);
    }

    public static void update(RenderContext context) {
        long ptr = BUFFER.ptr();
        setPrev();

        Vec3i renderOrigin = VisualizationManager.getOrThrow(context.level()).renderOrigin();
        Camera camera = context.camera();

        // VIEW must subtract the player eye, not the rendered camera viewpoint — context.modelView()
        // already contains any orientCamera-applied offset (third-person back-off, freecam shift,
        // etc.). Putting the viewpoint here too would double-apply the offset and visibly shift
        // every BE/Entity.
        Vec3d eyePos = camera.eyePosition();
        var eyeX = (float) (eyePos.x - renderOrigin.getX());
        var eyeY = (float) (eyePos.y - renderOrigin.getY());
        var eyeZ = (float) (eyePos.z - renderOrigin.getZ());

        VIEW.set(context.modelView());
        VIEW.translate(-eyeX, -eyeY, -eyeZ);
        PROJECTION.set(context.projection());
        VIEW_PROJECTION.set(context.viewProjection());
        VIEW_PROJECTION.translate(-eyeX, -eyeY, -eyeZ);

        // flw_cameraPos uniform reflects the actual rendered viewpoint so shader fog distance
        // (computed as ||vertexWorld - cameraPos||) matches vanilla's eye-space depth, which in
        // third-person is measured from the back-off camera, not the player.
        Vec3d viewpoint = camera.getPosition();
        CAMERA_POS.set(
                (float) (viewpoint.x - renderOrigin.getX()),
                (float) (viewpoint.y - renderOrigin.getY()),
                (float) (viewpoint.z - renderOrigin.getZ()));
        CAMERA_LOOK.set(camera.getLookVector());
        CAMERA_ROT.set(camera.getXRot(), camera.getYRot());

        if (firstWrite) {
            setPrev();
        }

        if (firstWrite || !frustumPaused || frustumCapture) {
            writePackedFrustumPlanes(ptr, VIEW_PROJECTION);
            frustumCapture = false;
        }

        ptr += 96;

        ptr = writeCullData(ptr);

        ptr = writeMatrices(ptr);

        ptr = writeRenderOrigin(ptr, renderOrigin);

        ptr = writeCamera(ptr);

        Minecraft mc = Minecraft.getMinecraft();
        ptr = writeVec2(ptr, mc.displayWidth, mc.displayHeight);
        ptr = writeFloat(ptr, (float) mc.displayWidth / (float) mc.displayHeight);
        ptr = writeFloat(ptr, Math.max(2.5F, (float) mc.displayWidth / 1920.0F * 2.5F));
        ptr = writeFloat(ptr, getDepthFar());

        ptr = writeTime(ptr, context);

        ptr = writeCameraIn(ptr, camera);

        ptr = writeInt(ptr, debugMode);

        ptr = writeFloat(ptr, 0.07f);

        firstWrite = false;
        BUFFER.markDirty();
    }

    private static long writeRenderOrigin(long ptr, Vec3i renderOrigin) {
        ptr = writeIVec3(ptr, renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ());
        return ptr;
    }

    private static void setPrev() {
        VIEW_PREV.set(VIEW);
        PROJECTION_PREV.set(PROJECTION);
        VIEW_PROJECTION_PREV.set(VIEW_PROJECTION);
        CAMERA_POS_PREV.set(CAMERA_POS);
        CAMERA_LOOK_PREV.set(CAMERA_LOOK);
        CAMERA_ROT_PREV.set(CAMERA_ROT);
    }

    private static long writeMatrices(long ptr) {
        ptr = writeMat4(ptr, VIEW);
        ptr = writeMat4(ptr, VIEW.invert(VIEW_INVERSE));
        ptr = writeMat4(ptr, VIEW_PREV);
        ptr = writeMat4(ptr, PROJECTION);
        ptr = writeMat4(ptr, PROJECTION.invert(PROJECTION_INVERSE));
        ptr = writeMat4(ptr, PROJECTION_PREV);
        ptr = writeMat4(ptr, VIEW_PROJECTION);
        ptr = writeMat4(ptr, VIEW_PROJECTION.invert(VIEW_PROJECTION_INVERSE));
        ptr = writeMat4(ptr, VIEW_PROJECTION_PREV);
        return ptr;
    }

    private static long writeCamera(long ptr) {
        ptr = writeVec3(ptr, CAMERA_POS.x, CAMERA_POS.y, CAMERA_POS.z);
        ptr = writeVec3(ptr, CAMERA_POS_PREV.x, CAMERA_POS_PREV.y, CAMERA_POS_PREV.z);
        ptr = writeVec3(ptr, CAMERA_LOOK.x, CAMERA_LOOK.y, CAMERA_LOOK.z);
        ptr = writeVec3(ptr, CAMERA_LOOK_PREV.x, CAMERA_LOOK_PREV.y, CAMERA_LOOK_PREV.z);
        ptr = writeVec2(ptr, CAMERA_ROT.x, CAMERA_ROT.y);
        ptr = writeVec2(ptr, CAMERA_ROT_PREV.x, CAMERA_ROT_PREV.y);
        return ptr;
    }

    private static long writeTime(long ptr, RenderContext context) {
        int ticks = tickProvider.getAsInt();
        float partialTick = context.partialTick();
        float renderTicks = ticks + partialTick;
        float renderSeconds = renderTicks / 20f;
        // 1.12.2: upstream uses Util.getMillis() which delegates to System::nanoTime, our replacement is equivalent
        long utilMillis = System.nanoTime() / 1000000L;
        float systemSeconds = (float) ((double) utilMillis / 1000.0);
        int systemMillis = (int) (utilMillis % Integer.MAX_VALUE);

        ptr = writeInt(ptr, ticks);
        ptr = writeFloat(ptr, partialTick);
        ptr = writeFloat(ptr, renderTicks);
        ptr = writeFloat(ptr, renderSeconds);
        ptr = writeFloat(ptr, systemSeconds);
        ptr = writeInt(ptr, systemMillis);
        return ptr;
    }

    private static long writeCameraIn(long ptr, Camera camera) {
        // Vanilla underwater fog triggers on player eye block, not the rendered camera. Use eye
        // position (and its block) so probing matches what vanilla would detect.
        Vec3d eye = camera.eyePosition();
        return writeInFluidAndBlock(ptr, camera.getEntity().world, new BlockPos(eye), eye);
    }

    private static long writeCullData(long ptr) {
        Minecraft mc = Minecraft.getMinecraft();

        int pyramidWidth = DepthPyramid.mip0Size(mc.displayWidth);
        int pyramidHeight = DepthPyramid.mip0Size(mc.displayHeight);
        int pyramidDepth = DepthPyramid.getImageMipLevels(pyramidWidth, pyramidHeight);

        ptr = writeFloat(ptr, Z_NEAR);
        ptr = writeFloat(ptr, getDepthFar());
        ptr = writeFloat(ptr, PROJECTION.m00());
        ptr = writeFloat(ptr, PROJECTION.m11());
        ptr = writeFloat(ptr, pyramidWidth);
        ptr = writeFloat(ptr, pyramidHeight);
        ptr = writeInt(ptr, pyramidDepth - 1);
        ptr = writeInt(ptr, 0);

        return ptr;
    }

    /**
     * Writes the frustum planes of the given projection matrix to the given buffer.<p>
     * Uses a different format that is friendly towards an optimized instruction-parallel
     * implementation of sphere-frustum intersection.<p>
     * The format is as follows:<p>
     * {@code vec4(nxX, pxX, nyX, pyX)}<br>
     * {@code vec4(nxY, pxY, nyY, pyY)}<br>
     * {@code vec4(nxZ, pxZ, nyZ, pyZ)}<br>
     * {@code vec4(nxW, pxW, nyW, pyW)}<br>
     * {@code vec2(nzX, pzX)}<br>
     * {@code vec2(nzY, pzY)}<br>
     * {@code vec2(nzZ, pzZ)}<br>
     * {@code vec2(nzW, pzW)}<br>
     * <p>
     * Writes 96 bytes to the buffer.
     *
     * @param ptr The buffer to write the planes to.
     * @param m   The projection matrix to compute the frustum planes for.
     */
    private static void writePackedFrustumPlanes(long ptr, Matrix4f m) {
        float nxX, nxY, nxZ, nxW;
        float pxX, pxY, pxZ, pxW;
        float nyX, nyY, nyZ, nyW;
        float pyX, pyY, pyZ, pyW;
        float nzX, nzY, nzZ, nzW;
        float pzX, pzY, pzZ, pzW;

        float invl;
        nxX = m.m03() + m.m00();
        nxY = m.m13() + m.m10();
        nxZ = m.m23() + m.m20();
        nxW = m.m33() + m.m30();
        invl = Math.invsqrt(nxX * nxX + nxY * nxY + nxZ * nxZ);
        nxX *= invl;
        nxY *= invl;
        nxZ *= invl;
        nxW *= invl;

        pxX = m.m03() - m.m00();
        pxY = m.m13() - m.m10();
        pxZ = m.m23() - m.m20();
        pxW = m.m33() - m.m30();
        invl = Math.invsqrt(pxX * pxX + pxY * pxY + pxZ * pxZ);
        pxX *= invl;
        pxY *= invl;
        pxZ *= invl;
        pxW *= invl;

        nyX = m.m03() + m.m01();
        nyY = m.m13() + m.m11();
        nyZ = m.m23() + m.m21();
        nyW = m.m33() + m.m31();
        invl = Math.invsqrt(nyX * nyX + nyY * nyY + nyZ * nyZ);
        nyX *= invl;
        nyY *= invl;
        nyZ *= invl;
        nyW *= invl;

        pyX = m.m03() - m.m01();
        pyY = m.m13() - m.m11();
        pyZ = m.m23() - m.m21();
        pyW = m.m33() - m.m31();
        invl = Math.invsqrt(pyX * pyX + pyY * pyY + pyZ * pyZ);
        pyX *= invl;
        pyY *= invl;
        pyZ *= invl;
        pyW *= invl;

        nzX = m.m03() + m.m02();
        nzY = m.m13() + m.m12();
        nzZ = m.m23() + m.m22();
        nzW = m.m33() + m.m32();
        invl = Math.invsqrt(nzX * nzX + nzY * nzY + nzZ * nzZ);
        nzX *= invl;
        nzY *= invl;
        nzZ *= invl;
        nzW *= invl;

        pzX = m.m03() - m.m02();
        pzY = m.m13() - m.m12();
        pzZ = m.m23() - m.m22();
        pzW = m.m33() - m.m32();
        invl = Math.invsqrt(pzX * pzX + pzY * pzY + pzZ * pzZ);
        pzX *= invl;
        pzY *= invl;
        pzZ *= invl;
        pzW *= invl;

        MemoryUtil.memPutFloat(ptr, nxX);
        MemoryUtil.memPutFloat(ptr + 4, pxX);
        MemoryUtil.memPutFloat(ptr + 8, nyX);
        MemoryUtil.memPutFloat(ptr + 12, pyX);
        MemoryUtil.memPutFloat(ptr + 16, nxY);
        MemoryUtil.memPutFloat(ptr + 20, pxY);
        MemoryUtil.memPutFloat(ptr + 24, nyY);
        MemoryUtil.memPutFloat(ptr + 28, pyY);
        MemoryUtil.memPutFloat(ptr + 32, nxZ);
        MemoryUtil.memPutFloat(ptr + 36, pxZ);
        MemoryUtil.memPutFloat(ptr + 40, nyZ);
        MemoryUtil.memPutFloat(ptr + 44, pyZ);
        MemoryUtil.memPutFloat(ptr + 48, nxW);
        MemoryUtil.memPutFloat(ptr + 52, pxW);
        MemoryUtil.memPutFloat(ptr + 56, nyW);
        MemoryUtil.memPutFloat(ptr + 60, pyW);
        MemoryUtil.memPutFloat(ptr + 64, nzX);
        MemoryUtil.memPutFloat(ptr + 68, pzX);
        MemoryUtil.memPutFloat(ptr + 72, nzY);
        MemoryUtil.memPutFloat(ptr + 76, pzY);
        MemoryUtil.memPutFloat(ptr + 80, nzZ);
        MemoryUtil.memPutFloat(ptr + 84, pzZ);
        MemoryUtil.memPutFloat(ptr + 88, nzW);
        MemoryUtil.memPutFloat(ptr + 92, pzW);
    }

    public static boolean debugOn() {
        return debugMode != DebugMode.OFF.ordinal();
    }
}
