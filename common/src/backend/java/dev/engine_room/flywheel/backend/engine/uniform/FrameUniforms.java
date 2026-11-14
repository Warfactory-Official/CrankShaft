package dev.engine_room.flywheel.backend.engine.uniform;

import dev.engine_room.flywheel.api.backend.GameClock;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.engine.indirect.DepthPyramid;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.joml.Math;
import org.lwjgl.system.MemoryUtil;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

public final class FrameUniforms extends UniformWriter {
    private static final int SIZE = 96 + 64 * 9 + 16 * 5 + 8 * 2 + 8 + 4 * 21;
    static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.FRAME_INDEX, SIZE);
    static final UniformBuffer VIEW_BUFFER = new UniformBuffer(Uniforms.FRAME_INDEX, SIZE);

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
    private static DebugMode debugMode = DebugMode.OFF;
    private static boolean frustumPaused = false;
    private static boolean frustumCapture = false;
    // 26.2: no LevelRenderer.ticks; counted per unpaused client tick.
    private static int ticks;
    private static float partialTick;
    private static float renderTicks;
    private static float renderSeconds;
    private static int gameMillis;
    private static DoubleSupplier renderScale = () -> 1;

    private FrameUniforms() {
    }

    /**
     * Installs the fraction of each main-target axis the world is rendered into, anchored at the lower-left: a pack
     * implementing Super Resolution's interface squeezes its gbuffer clip space so. Main-view HiZ maps into that rect.
     * Installed once by the {@code :iris} module; read on the render thread; 1 otherwise.
     */
    public static void setRenderScale(DoubleSupplier scale) {
        renderScale = scale;
    }

    /**
     * A main-target extent as the world is rendered into it ({@link #setRenderScale}).
     */
    public static int renderExtent(int targetExtent) {
        return (int) ((float) renderScale.getAsDouble() * targetExtent);
    }

    public static Matrix4fc view() {
        return VIEW;
    }

    public static void debugMode(DebugMode mode) {
        debugMode = mode;
    }

    public static DebugMode debugMode() {
        return debugMode;
    }

    public static void captureFrustum() {
        frustumPaused = true;
        frustumCapture = true;
    }

    public static void unpauseFrustum() {
        frustumPaused = false;
    }

    public static void tick() {
        ticks++;
    }

    public static float getDepthFar() {
        // 26.2: no getDepthFar; reversed-Z zero-to-one clip gives D = m32 / m22 (the
        // [-1,1] decode m32/(m22+1) returned ~n). Valid after the first PROJECTION.set().
        return PROJECTION.m32() / PROJECTION.m22();
    }

    public static long ptr() {
        return BUFFER.ptr();
    }

    public static int size() {
        return SIZE;
    }

    public static float partialTick() {
        return partialTick;
    }

    public static float renderTicks() {
        return renderTicks;
    }

    public static float renderSeconds() {
        return renderSeconds;
    }

    /**
     * {@link GameClock}'s low 32 bits for this frame ({@code flw_gameMillis}).
     */
    public static int gameMillis() {
        return gameMillis;
    }

    public static void bind() {
        BUFFER.bind();
    }

    /**
     * Binds this frame's block seen through {@code projection}, previous = current, until {@link #bind}; the main
     * block keeps its state. Render thread.
     */
    public static void bindView(RenderContext context, Matrix4fc projection) {
        Vec3i renderOrigin = VisualizationManager.getOrThrow(context.level()).renderOrigin();
        Camera camera = context.camera();
        Vec3 cameraPos = camera.position();
        Vector3f pos = new Vector3f((float) (cameraPos.x - renderOrigin.getX()),
                (float) (cameraPos.y - renderOrigin.getY()), (float) (cameraPos.z - renderOrigin.getZ()));
        Matrix4f view = new Matrix4f(context.modelView()).translate(-pos.x, -pos.y, -pos.z);
        Matrix4f proj = new Matrix4f(projection);
        Matrix4f viewProjection = new Matrix4f(proj).mul(view);
        Vector3f look = new Vector3f(camera.forwardVector());
        Vector2f rot = new Vector2f(camera.xRot(), camera.yRot());
        long ptr = VIEW_BUFFER.ptr();
        writePackedFrustumPlanes(ptr, viewProjection);
        writeBody(ptr + 96, context, renderOrigin, view, proj, viewProjection, pos, look, rot, view, proj,
                viewProjection, pos, look, rot);
        VIEW_BUFFER.markDirty();
        VIEW_BUFFER.bind();
    }

    public static void update(RenderContext context) {
        partialTick = context.partialTick();
        LongSupplier clock = GameClock.installed();
        gameMillis = (int) (clock != null ? clock.getAsLong()
                : context.level().getGameTime() * 50L + (long) (partialTick * 50F));
        long ptr = BUFFER.ptr();
        setPrev();

        Vec3i renderOrigin = VisualizationManager.getOrThrow(context.level()).renderOrigin();
        Camera camera = context.camera();

        Vec3 cameraPos = camera.position();
        var camX = (float) (cameraPos.x - renderOrigin.getX());
        var camY = (float) (cameraPos.y - renderOrigin.getY());
        var camZ = (float) (cameraPos.z - renderOrigin.getZ());

        VIEW.set(context.modelView());
        VIEW.translate(-camX, -camY, -camZ);
        PROJECTION.set(context.projection());
        VIEW_PROJECTION.set(context.viewProjection());
        VIEW_PROJECTION.translate(-camX, -camY, -camZ);

        CAMERA_POS.set(camX, camY, camZ);
        CAMERA_LOOK.set(camera.forwardVector());
        CAMERA_ROT.set(camera.xRot(), camera.yRot());

        if (firstWrite) {
            setPrev();
        }

        if (firstWrite || !frustumPaused || frustumCapture) {
            writePackedFrustumPlanes(ptr, VIEW_PROJECTION);
            frustumCapture = false;
        }

        renderTicks = ticks + partialTick;
        renderSeconds = renderTicks / 20.0f;
        writeBody(ptr + 96, context, renderOrigin, VIEW, PROJECTION, VIEW_PROJECTION, CAMERA_POS, CAMERA_LOOK,
                CAMERA_ROT, VIEW_PREV, PROJECTION_PREV, VIEW_PROJECTION_PREV, CAMERA_POS_PREV, CAMERA_LOOK_PREV,
                CAMERA_ROT_PREV);

        firstWrite = false;
        BUFFER.markDirty();
    }

    private static void writeBody(long ptr, RenderContext context, Vec3i renderOrigin, Matrix4f view,
                                  Matrix4f projection, Matrix4f viewProjection, Vector3f cameraPos,
                                  Vector3f cameraLook, Vector2f cameraRot, Matrix4f viewPrev, Matrix4f projectionPrev,
                                  Matrix4f viewProjectionPrev, Vector3f cameraPosPrev, Vector3f cameraLookPrev,
                                  Vector2f cameraRotPrev) {
        float depthFar = projection.m32() / projection.m22();

        ptr = writeCullData(ptr, projection, depthFar);

        ptr = writeMat4(ptr, view);
        ptr = writeMat4(ptr, view.invert(VIEW_INVERSE));
        ptr = writeMat4(ptr, viewPrev);
        ptr = writeMat4(ptr, projection);
        ptr = writeMat4(ptr, projection.invert(PROJECTION_INVERSE));
        ptr = writeMat4(ptr, projectionPrev);
        ptr = writeMat4(ptr, viewProjection);
        ptr = writeMat4(ptr, viewProjection.invert(VIEW_PROJECTION_INVERSE));
        ptr = writeMat4(ptr, viewProjectionPrev);

        ptr = writeRenderOrigin(ptr, renderOrigin);

        ptr = writeVec3(ptr, cameraPos);
        ptr = writeVec3(ptr, cameraPosPrev);
        ptr = writeVec3(ptr, cameraLook);
        ptr = writeVec3(ptr, cameraLookPrev);
        ptr = writeVec2(ptr, cameraRot.x, cameraRot.y);
        ptr = writeVec2(ptr, cameraRotPrev.x, cameraRotPrev.y);

        var window = Minecraft.getInstance().getWindow();
        // Port: viewport = the world target (see writeCullData); line width stays window-based, as vanilla's.
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        ptr = writeVec2(ptr, target.width, target.height);
        ptr = writeFloat(ptr, (float) target.width / (float) target.height);
        ptr = writeFloat(ptr, Math.max(2.5F, (float) window.getWidth() / 1920.0F * 2.5F));
        ptr = writeFloat(ptr, depthFar);

        ptr = writeTime(ptr, context);

        ptr = writeCameraIn(ptr, context.camera());

        ptr = writeInt(ptr, debugMode.ordinal());

        writeFloat(ptr, 0.07f);
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

    private static long writeTime(long ptr, RenderContext context) {
        float partialTick = context.partialTick();
        float renderTicks = ticks + partialTick;
        float renderSeconds = renderTicks / 20.0f;
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
        if (!camera.isInitialized()) {
            ptr = writeInt(ptr, 0);
            ptr = writeInt(ptr, 0);
            return ptr;
        }

        Level level = camera.entity().level();
        BlockPos blockPos = camera.blockPosition();
        Vec3 cameraPos = camera.position();
        return writeInFluidAndBlock(ptr, level, blockPos, cameraPos);
    }

    private static long writeCullData(long ptr, Matrix4f projection, float depthFar) {
        // Port: the pyramid's target, not the window: a mod may render the world at another scale, or into part of it.
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();

        int pyramidDepth = DepthPyramid.getImageMipLevels(DepthPyramid.mip0Size(target.width),
                DepthPyramid.mip0Size(target.height));

        ptr = writeFloat(ptr, Z_NEAR); // zNear
        ptr = writeFloat(ptr, depthFar); // zFar
        ptr = writeFloat(ptr, projection.m00()); // P00
        ptr = writeFloat(ptr, projection.m11()); // P11
        ptr = writeFloat(ptr, renderExtent(target.width));
        ptr = writeFloat(ptr, renderExtent(target.height));
        ptr = writeInt(ptr, pyramidDepth - 1); // pyramidLevels
        ptr = writeInt(ptr, 0); // useMin

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

        // 26.2 reversed-Z [0,1] clip: the near-side z-plane is row3 alone
        // (upstream's [-1,1] formula used row4 + row3).
        nzX = m.m02();
        nzY = m.m12();
        nzZ = m.m22();
        nzW = m.m32();
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
        return debugMode != DebugMode.OFF;
    }
}
