package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.seibel.distanthorizons.api.DhApi;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/**
 * Pipeline-owned DH translucent stages; reconstructed records enter the pack's native water attachments.
 */
public final class FarLayerDhProgram implements AutoCloseable {
    public static final String SUFFIX = "/flywheel_layers";
    private final IrisLodRenderProgram shader;
    private final IrisRenderingPipeline pipeline;
    private final GlProgram program;
    private final FarLayerPixels pixels = new FarLayerPixels();
    /**
     * Owned and destroyed by the pipeline's render targets.
     */
    private final @Nullable GlFramebuffer mainFramebuffer;
    private final boolean shadow;
    private @Nullable FarHostOit hostOit;
    private int hostOpaque;
    private int mainOpaque;
    private boolean hostZeroToOne;
    private final Matrix4f hostClip = new Matrix4f();
    private final Matrix4f mainClip = new Matrix4f();
    private final int targets;
    private final boolean materialMaps;
    private final int vao = GL45C.glCreateVertexArrays();
    private final Matrix4f lodProjection = new Matrix4f();
    private final Matrix4f lodModelView = new Matrix4f();
    private int worldYOffset;
    private float partialTicks;

    public FarLayerDhProgram(IrisLodRenderProgram shader, int id, int[] drawBuffers, IrisRenderingPipeline pipeline,
                             boolean materialMaps, boolean shadow) {
        this.shader = shader;
        this.pipeline = pipeline;
        this.materialMaps = materialMaps;
        this.shadow = shadow;
        program = new GlProgram(id);
        targets = drawBuffers.length;
        mainFramebuffer = shadow ? null : ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets()
                                                                                    .createGbufferFramebuffer(
                                                                                            pipeline.getFlippedAfterTranslucent(),
                                                                                            drawBuffers);
    }

    public void hostOit(FarHostOit graph) {
        hostOit = graph;
        graph.registerSamplers(program);
    }

    public void opaque(int hostDepth, int mainDepth, Matrix4fc hostClip, Matrix4fc mainClip, boolean hostZeroToOne) {
        hostOpaque = hostDepth;
        mainOpaque = mainDepth;
        this.hostZeroToOne = hostZeroToOne;
        this.hostClip.set(hostClip);
        this.mainClip.set(mainClip);
    }

    public boolean ready() {
        return pixels.ready();
    }

    public boolean shadow() {
        return shadow;
    }

    /**
     * Native DH uniform setup supplies the host projection before the render-thread translucent callback.
     */
    public void prepareLod(Matrix4fc projection, Matrix4fc modelView, int worldYOffset, float partialTicks) {
        lodProjection.set(projection);
        lodModelView.set(modelView);
        this.worldYOffset = worldYOffset;
        this.partialTicks = partialTicks;
    }

    public Matrix4fc lodProjection() {
        return lodProjection;
    }

    public Matrix4fc lodModelView() {
        return lodModelView;
    }

    /**
     * After readiness, shades every record in one complete window. Its depth and inverse projection use the same
     * clip convention; world positions and the supplied model view are camera relative. Main/host coverage is disjoint.
     * The capture traversal owns the borrowed GL state scope across all windows.
     */
    public void draw(int hits, int maximumHits, int width, int height, Matrix4fc inverseProjection,
                     Matrix4fc viewToWorld, Matrix4fc projection, Matrix4fc modelView, boolean main) {
        try (var stack = MemoryStack.stackPush()) {
            pixels.prepare(hits, maximumHits);
            GuestSsbos.restore(pipeline);
            var dh = (DHCompatInternal) pipeline.getDHCompat().getInstance();
            if (shadow) dh.getShadowFB().bindAsDrawBuffer();
            else if (main) mainFramebuffer.bindAsDrawBuffer();
            else dh.getTranslucentFB().bindAsDrawBuffer();
            GlStateManager._glUseProgram(program.handle());
            shader.bind();
            shader.fillUniformData(projection, modelView, main ? 0 : worldYOffset, partialTicks);
            GuestSsbos.bindForGuest(pipeline);
            if (materialMaps) FarMaterialTextures.bind();
            if (hostOit != null) {
                hostOit.bindProgram(program);
                program.setMat4("_flw_farHostModelView", modelView);
                program.setBool("_flw_farLayerOpaqueTest", true);
                program.setMat4("_flw_farLayerHostClip", hostClip);
                program.setMat4("_flw_farLayerMainClip", mainClip);
                program.setBool("_flw_farLayerHostZeroToOne", hostZeroToOne);
                program.setFloat("_flw_farLayerRasterScale", hostOit.packed() ? 0.5f : 1.0f);
                program.setSamplerBinding("_flw_farLayerHostOpaque", 8);
                program.setSamplerBinding("_flw_farLayerMainOpaque", 9);
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 8);
                GlStateManager._bindTexture(hostOpaque);
                GL33C.glBindSampler(8, 0);
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 9);
                GlStateManager._bindTexture(mainOpaque);
                GL33C.glBindSampler(9, 0);
            }
            GL20C.glUniform3f(shader.modelOffsetUniform, 0, 0, 0);
            GL20C.glUniform1f(shader.clipDistanceUniform, 0);
            program.setMat4("_flw_farLayerInverseProjection", inverseProjection);
            program.setMat4("_flw_farLayerViewToWorld", viewToWorld);
            program.setMat4("_flw_farLayerWorldToView", new Matrix4f(viewToWorld).invert());
            program.setVec2("_flw_farHitSize", width, height);
            program.setFloat("_flw_farMainRadius", main ? LodCompat.vanillaRadius() : 0);
            program.setFloat("_flw_farLayerRadius", LodCompat.vanillaRadius());
            program.setBool("_flw_farLayerMain", main);
            program.setBool("_flw_farLayerShadow", shadow);
            if (main) {
                GL20C.glUniformMatrix4fv(program.getUniformLocation("dhProjection"), false,
                        projection.get(stack.mallocFloat(16)));
                GL20C.glUniformMatrix4fv(program.getUniformLocation("dhProjectionInverse"), false,
                        new Matrix4f(projection).invert().get(stack.mallocFloat(16)));
            }
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, FarLayerPixels.HIT_BINDING, hits);
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._viewport(0, 0, width, height);
            if (hostOit == null || hostOit.deferred() && hostOit.pass() == 1)
                GlStateManager._enableDepthTest();
            else GlStateManager._disableDepthTest();
            GlStateManager._depthFunc((shadow ? NativeDepth.active() : (hostOit == null || !hostOit.deferred()) && main
                    || DhApi.Delayed.renderProxy.getDepthDirection().farDepth < 0.5f)
                    ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
            GlStateManager._depthMask(shadow || hostOit != null && hostOit.deferred() && hostOit.pass() == 1);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            for (int target = 0; target < targets; target++)
                GlStateManager._colorMask(target,
                        hostOit != null && hostOit.deferred() && hostOit.pass() == 0 ? 0 : 15);
            pixels.draw();
            shader.unbind();
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
        }
    }

    @Override
    public void close() {
        shader.free();
        pixels.close();
        GL30C.glDeleteVertexArrays(vao);
    }
}
