package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;

public final class GuestRenderPass implements AutoCloseable {
    private static float renderPartialTicks;
    public final boolean shadow;
    public final Matrix4f view = new Matrix4f();
    final GuestHost host;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f originView = new Matrix4f();
    private final Vector3f originToCamera = new Vector3f();
    private final float[] textureMatrices = new float[128];
    private final boolean culling;
    private final boolean depthTest;
    private final boolean depthWrite;
    private final int depthFunction;
    private final boolean polygonOffset;
    private final float polygonFactor;
    private final float polygonUnits;
    private final int colorMask;
    private final boolean alphaTest;
    private final int alphaFunction;
    private final float alphaReference;
    public int program;
    public int vertexShader;
    public int fragmentShader;
    public int geometryShader;
    public int tessControlShader;
    public int tessEvaluationShader;
    public int epoch;
    private GuestRole role;
    private GuestPhase phase;
    private int nativeAlphaFunction;
    private float nativeAlphaReference;
    private int[] nativeBuffers;
    private int[] targetBuffers;
    private int savedPatchVertices;

    public GuestRenderPass(boolean translucent, boolean crumbling) {
        this(translucent, crumbling, null);
    }

    GuestRenderPass(GuestPhase terrainPhase) {
        this(false, false, terrainPhase);
    }

    private GuestRenderPass(boolean translucent, boolean crumbling, GuestPhase terrainPhase) {
        host = GuestHost.current();
        culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        alphaFunction = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        alphaReference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        polygonFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        polygonUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            colorMask = (mask.get(0) != 0 ? 1 : 0) | (mask.get(1) != 0 ? 2 : 0)
                    | (mask.get(2) != 0 ? 4 : 0) | (mask.get(3) != 0 ? 8 : 0);
        }
        shadow = host.shadow();
        phase = terrainPhase != null ? terrainPhase : crumbling ? GuestPhase.DESTROY : translucent
                ? GuestPhase.TERRAIN_TRANSLUCENT : GuestPhase.BLOCK_ENTITIES;
        role = shadow ? phase.shadowPass() : phase.mainPass();
        // Host adapters own native program and framebuffer transitions.
        host.begin(role, phase);
        try {
            captureProgram();
            var matrix = new float[16];
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, matrix);
            view.set(matrix);
            GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, matrix);
            projection.set(matrix);
            var minecraft = Minecraft.getMinecraft();
            var manager = VisualizationManager.get(minecraft.world);
            var origin = manager.renderOrigin();
            // Shadow terrain can precede frame dispatch; replacement renderers can also leave the vanilla camera stale.
            var camera = minecraft.getRenderViewEntity();
            var eye = camera.getPositionEyes(renderPartialTicks);
            originToCamera.set((float) (origin.getX() - eye.x),
                    (float) (origin.getY() - eye.y + camera.getEyeHeight()),
                    (float) (origin.getZ() - eye.z));
            originView.set(view).translate(originToCamera);
            var activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            for (var unit = 0; unit < 8; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glGetFloatv(GL11.GL_TEXTURE_MATRIX, matrix);
                System.arraycopy(matrix, 0, textureMatrices, unit * 16, 16);
            }
            GL13.glActiveTexture(activeTexture);
        } catch (RuntimeException | Error error) {
            host.end();
            throw error;
        }
    }

    public static void setRenderPartialTicks(float partialTicks) {
        renderPartialTicks = partialTicks;
    }

    public static boolean active() {
        return GuestHost.active();
    }

    public static boolean shadowActive() {
        return active() && GuestHost.current().shadow();
    }

    public Vector3f originToCamera() {
        return originToCamera;
    }

    GuestRole role() {
        return role;
    }

    void targets(int[] buffers) {
        if (Arrays.equals(targetBuffers, buffers)) return;
        host.targets(role, buffers);
        targetBuffers = buffers;
    }

    void materialBlend(Material material) {
        targets(nativeBuffers);
        host.materialBlend(role, material, nativeBuffers);
    }

    public void rebind() {
        host.bind(role, phase);
        targetBuffers = nativeBuffers;
        GlStateTracker._setProgram(program);
    }

    public void activate(GuestPhase phase) {
        if (this.phase == phase) return;
        this.phase = phase;
        role = shadow ? phase.shadowPass() : phase.mainPass();
        host.bind(role, phase);
        captureProgram();
        epoch++;
    }

    private void captureProgram() {
        nativeAlphaFunction = GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? GL11.glGetInteger(
                GL11.GL_ALPHA_TEST_FUNC) : GL11.GL_ALWAYS;
        nativeAlphaReference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        program = host.program(role);
        if (program == 0) throw new IllegalStateException("Shader host did not bind a world program");
        var shaders = host.shaders(role, program);
        vertexShader = shaders[0];
        fragmentShader = shaders[1];
        geometryShader = shaders[2];
        tessControlShader = shaders[3];
        tessEvaluationShader = shaders[4];
        if (vertexShader == 0 || fragmentShader == 0)
            throw new IllegalStateException("Shader host world shader sources are unavailable");
        nativeBuffers = host.drawBuffers(role);
        targetBuffers = nativeBuffers;
        GlStateTracker._setProgram(program);
    }

    public void upload(GlProgram target) {
        target.setInt("_flw_nativeAlphaFunction", nativeAlphaFunction);
        target.setFloat("_flw_nativeAlphaReference", nativeAlphaReference);
        target.setMat4("flw_viewMat", view);
        target.setMat4("flw_viewMatInverse", new Matrix4f(view).invert());
        target.setMat4("_flw_packViewInverse", new Matrix4f(view).invert());
        target.setMat4("flw_viewMatTranspose", new Matrix4f(view).transpose());
        target.setMat4("_flw_packProjection", projection);
        target.setMat4("_flw_packProjectionInverse", new Matrix4f(projection).invert());
        target.setMat4("_flw_oitProjectionInverse", new Matrix4f(projection).invert());
        var mvp = new Matrix4f(projection).mul(view);
        target.setMat4("flw_modelViewProj", mvp);
        target.setMat4("flw_modelViewProjInverse", mvp.invert());
        target.setMat4("_flw_viewMatOriginRel", originView);
        target.setMat3("flw_normalMatrix", new Matrix3f(view).invert().transpose());
        target.setVec3("_flw_originRelToCameraRel", originToCamera.x, originToCamera.y, originToCamera.z);
        var location = target.getUniformLocation("flw_textureMatrix[0]");
        if (location >= 0) GL20.glUniformMatrix4fv(location, false, textureMatrices);
    }

    int primitive(boolean tessellated) {
        if (!tessellated) return GL11.GL_TRIANGLES;
        if (savedPatchVertices == 0) savedPatchVertices = GL11.glGetInteger(GL40.GL_PATCH_VERTICES);
        GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES, 4);
        return GL40.GL_PATCHES;
    }

    @Override
    public void close() {
        try {
            if (savedPatchVertices != 0) GL40.glPatchParameteri(GL40.GL_PATCH_VERTICES, savedPatchVertices);
            MaterialRenderState.resetTexture();
        } finally {
            try {
                host.end();
            } finally {
                host.storage.restore();
                if (culling) GlStateManager.enableCull();
                else GlStateManager.disableCull();
                if (depthTest) GlStateManager.enableDepth();
                else GlStateManager.disableDepth();
                GlStateManager.depthFunc(depthFunction);
                GlStateManager.depthMask(depthWrite);
                GlStateManager.alphaFunc(alphaFunction, alphaReference);
                if (alphaTest) GlStateManager.enableAlpha();
                else GlStateManager.disableAlpha();
                GlStateManager.colorMask((colorMask & 1) != 0, (colorMask & 2) != 0, (colorMask & 4) != 0,
                        (colorMask & 8) != 0);
                GlStateManager.doPolygonOffset(polygonFactor, polygonUnits);
                if (polygonOffset) GlStateManager.enablePolygonOffset();
                else GlStateManager.disablePolygonOffset();
            }
        }
    }
}
