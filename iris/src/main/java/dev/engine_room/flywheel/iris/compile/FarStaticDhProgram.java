package dev.engine_room.flywheel.iris.compile;

#if MC < 26.3
import com.mojang.blaze3d.systems.RenderSystem;
#endif
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.impl.compat.DhStaticVisuals;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.far.FarHost;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
#if MC < 26.3
import dev.engine_room.flywheel.iris.compile.patches.GuestLodOcclusion;
#endif
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

import java.util.List;

/**
 * The native DH pack stages shade the original frozen triangles in the host's selected sections.
 */
public final class FarStaticDhProgram implements AutoCloseable {
    public static final String SUFFIX = "/flywheel_static";

    private final IrisLodRenderProgram shader;
    private final IrisRenderingPipeline pipeline;
    private final GlProgram program;
    private final FarStaticDraw draw = new FarStaticDraw(false);
    private final int targets;
    private final boolean materialMaps;
    private final Matrix4f lodProjection = new Matrix4f();
    private final Matrix4f lodModelView = new Matrix4f();
    private int lodWorldYOffset;
    private float lodPartialTicks;
    private FarStaticDraw.TerrainDepth mainDepth;

    public FarStaticDhProgram(IrisLodRenderProgram shader, int id, int targets,
                              IrisRenderingPipeline pipeline, boolean materialMaps) {
        this.shader = shader;
        this.pipeline = pipeline;
        program = new GlProgram(id);
        this.targets = targets;
        this.materialMaps = materialMaps;
    }

    /**
     * Captures the native program's current view before its terrain draw, on the render thread.
     */
    public void prepareLod(Matrix4fc projection, Matrix4fc modelView, int worldYOffset, float partialTicks) {
        lodProjection.set(projection);
        lodModelView.set(modelView);
        lodWorldYOffset = worldYOffset;
        lodPartialTicks = partialTicks;
    }

    private void prepareMain(FarHost host, RenderContext context) {
        #if MC < 26.3
        // Iris replaces DH's API projection, then DepthTransformer remaps its forward clip depth for hardware; a pack
        // taking raw reversed depth gets no remap.
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        float depthScale = zeroToOne ? -0.5f : -1.0f;
        float depthOffset = zeroToOne ? 0.5f : 0.0f;
        var worldToClip = (NativeDepth.active() ? new Matrix4f(lodProjection)
                : new Matrix4f().m22(depthScale).m32(depthOffset).mul(lodProjection)).mul(lodModelView);
        mainDepth = new FarStaticDraw.TerrainDepth(host.depth(), worldToClip, zeroToOne, true);
        #else
        mainDepth = new FarStaticDraw.TerrainDepth(host.depth(),
                new Matrix4f(host.viewToHostClip()).mul(context.modelView()), host.zeroToOne(), host.reverseZ());
        #endif
    }

    private void draw(FarStaticMeshes meshes, List<DhStaticVisuals.Batch> batches, @Nullable RenderContext main) {
        var mc = Minecraft.getInstance();
        var dh = (DHCompatInternal) pipeline.getDHCompat().getInstance();
        dh.getSolidFB().bindAsDrawBuffer();
        var target = mc.gameRenderer.mainRenderTarget();
        if (main != null) {
            draw.mainTarget(targets, ((GlTexture) target.getDepthTexture()).glId(), target.width, target.height);
        }
        Matrix4fc projection = main == null ? lodProjection : NativeDepth.active() ? main.projection()
                : MatrixUtils.undoRevZ(new Matrix4f(main.projection()));
        var modelView = main == null ? lodModelView : main.modelView();
        var camera = main == null ? VisualizationManagerImpl.getOrThrow(mc.level).currentFrameContext().camera()
                                                            .position()
                : main.camera().position();
        GlStateManager._glUseProgram(program.handle());
        shader.bind();
        shader.fillUniformData(projection, modelView, main == null ? lodWorldYOffset : 0,
                main == null ? lodPartialTicks : main.partialTick());
        if (main != null) {
            try (var stack = MemoryStack.stackPush()) {
                GL20C.glUniformMatrix4fv(program.getUniformLocation("dhProjection"), false,
                        projection.get(stack.mallocFloat(16)));
                GL20C.glUniformMatrix4fv(program.getUniformLocation("dhProjectionInverse"), false,
                        new Matrix4f(projection).invert().get(stack.mallocFloat(16)));
            }
        }
        GL20C.glUniform3f(shader.modelOffsetUniform, 0, 0, 0);
        GL20C.glUniform1f(shader.clipDistanceUniform, 0);
        float radius = main == null ? 0 : LodCompat.vanillaRadius();
        program.setFloat("_flw_staticMainRadius", radius);
        program.setFloat("_flw_farMainRadius", radius);
        FarStaticDraw.TerrainDepth terrainDepth = null;
        if (main != null) {
            #if MC < 26.3
            int depth = GuestLodOcclusion.terrainDepth();
            #else
            int depth = pipeline.getDHCompat().getDepthTex();
            #endif
            if (depth == 0) throw new IllegalStateException("Static DH main draw has no current opaque depth");
            terrainDepth = new FarStaticDraw.TerrainDepth(depth, mainDepth.worldToClip(),
                    mainDepth.zeroToOne(), mainDepth.reverseZ());
        }
        FarStaticDraw.terrainDepth(program, terrainDepth, 12);
        FarStaticDraw.camera(program, camera);
        FarStaticDraw.bindOverlay(program, 11);
        GuestSsbos.bindForGuest(pipeline);
        if (materialMaps) FarMaterialTextures.bind();
        GlStateManager._viewport(0, 0, target.width, target.height);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GlStateManager._enableDepthTest();
        boolean reverseZ = main != null || DhApi.Delayed.renderProxy.getDepthDirection().farDepth < 0.5f;
        GlStateManager._depthFunc(reverseZ ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._disableScissorTest();
        for (int i = 0; i < targets; i++) {
            GlStateManager._disableBlend(i);
            GlStateManager._colorMask(i, 15);
        }
        for (var batch : batches) {
            draw.geometry(meshes, batch.mappingBuffer(), batch.clipBuffer(), 0, 1, 2);
            draw.material(program, batch.template(), 10, 11);
            FarStaticDraw.indirect(batch.commands());
        }
        GuestSsbos.restore(pipeline);
        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();
    }

    @Override
    public void close() {
        shader.free();
        draw.close();
    }

    public static final class Provider implements DhStaticVisuals.DrawProvider {
        private static @Nullable FarStaticDhProgram current() {
            if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline
                    && pipeline.getDHCompat().getInstance() instanceof DHCompatInternal dh && dh.shouldOverride) {
                FarDhOwner owner = dh.getSolidShader();
                return owner.flywheel$staticFar();
            }
            return null;
        }

        @Override
        public boolean available() {
            return GlCompat.SUPPORTS_INDIRECT && GlCompat.SUPPORTS_SHADER_DRAW_PARAMETERS && current() != null;
        }

        @Override
        public void prepareMain(FarHost host, RenderContext context) {
            current().prepareMain(host, context);
        }

        @Override
        public void draw(FarStaticMeshes meshes, List<DhStaticVisuals.Batch> batches, RenderParams params) {
            current().draw(meshes, batches, null);
        }

        @Override
        public void drawMain(FarStaticMeshes meshes, List<DhStaticVisuals.Batch> batches, RenderContext context) {
            current().draw(meshes, batches, context);
        }
    }
}
