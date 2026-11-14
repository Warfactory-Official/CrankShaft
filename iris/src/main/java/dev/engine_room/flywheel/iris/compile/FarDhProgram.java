package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.seibel.distanthorizons.api.DhApi;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.impl.far.FarSurface;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/**
 * GPU compaction and the opaque pack DH program used to shade retained surface pixels.
 */
public final class FarDhProgram {
    public static final String SUFFIX = "/flywheel_far";
    private static final int[] UNITS = {10, 11, 12, 13, 14, 15};
    private static final int[] SAVED_UNITS = {0, 10, 11, 12, 13, 14, 15};
    private static final int[] SAVED_BUFFER_UNITS = {14};
    private static final String COMPACT = """
            layout(local_size_x = 256) in;
            layout(binding = 13) uniform sampler2D _flw_farDepth;
            layout(std430, binding = 0) buffer FarCommand {
                uint count; uint instanceCount; uint firstIndex; int baseVertex; uint baseInstance;
            };
            layout(std430, binding = 1) writeonly buffer FarPixels { uint pixels[]; };
            shared uint groupCount;
            shared uint groupBase;
            void main() {
                if (gl_LocalInvocationID.x == 0u) groupCount = 0u;
                barrier();
                ivec2 size = textureSize(_flw_farDepth, 0);
                uint pixel = gl_GlobalInvocationID.x;
                bool kept = false;
                if (pixel < uint(size.x * size.y)) {
                    ivec2 at = ivec2(int(pixel) % size.x, int(pixel) / size.x);
                    kept = texelFetch(_flw_farDepth, at, 0).r != 0.0;
                }
                uint localIndex = 0u;
                if (kept) localIndex = atomicAdd(groupCount, 1u);
                barrier();
                if (gl_LocalInvocationID.x == 0u && groupCount != 0u) {
                    groupBase = atomicAdd(instanceCount, groupCount);
                }
                barrier();
                if (kept) pixels[groupBase + localIndex] = pixel;
            }
            """;

    private final IrisLodRenderProgram shader;
    private final IrisRenderingPipeline pipeline;
    private final int id;
    private final int targets;
    private final int pixelBuffer;
    private final int pixelTexture;
    private final int command;
    private final int vao;
    private final int indexBuffer;
    private final int sampler;
    private final AsyncGlProgram compact;
    private final int inverseProjection;
    private final int viewToWorld;
    private final int mainRadius;
    private final int dhProjection;
    private final int dhProjectionInverse;
    /**
     * Owned and destroyed by the pipeline's render targets.
     */
    private final GlFramebuffer mainFramebuffer;
    private int capacity;
    private FarSurface prepared;
    private final Matrix4f lodProjection = new Matrix4f();
    private final Matrix4f lodModelView = new Matrix4f();
    private int lodWorldYOffset;
    private float lodPartialTick;

    public FarDhProgram(IrisLodRenderProgram shader, int id, int[] drawBuffers, IrisRenderingPipeline pipeline) {
        this.shader = shader;
        this.pipeline = pipeline;
        this.id = id;
        targets = drawBuffers.length;
        mainFramebuffer = ((IrisRenderingPipelineAccessor) pipeline).flywheel$renderTargets()
                                                                    .createGbufferFramebuffer(
                                                                            pipeline.getFlippedAfterPrepare(),
                                                                            drawBuffers);
        pixelBuffer = GL45C.glCreateBuffers();
        pixelTexture = GL45C.glCreateTextures(GL31C.GL_TEXTURE_BUFFER);
        command = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(command, new int[]{6, 0, 0, 0, 0}, GL15C.GL_DYNAMIC_DRAW);
        vao = GL45C.glCreateVertexArrays();
        indexBuffer = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(indexBuffer, new int[]{0, 1, 2, 0, 2, 3}, GL15C.GL_STATIC_DRAW);
        GL45C.glVertexArrayElementBuffer(vao, indexBuffer);
        sampler = GL45C.glCreateSamplers();
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        Compilation source = new Compilation();
        source.version(GlslVersion.V430);
        source.appendComponent(new RawSource("far_dh_compact", COMPACT));
        compact = AsyncGlProgram.compute("iris/far_dh_compact", source, _ -> {
        });
        String[] names = {"_flw_farColor", "_flw_farNormal", "_flw_farLight", "_flw_farDepth", "_flw_farPixels",
                "_flw_farMaterial"};
        for (int i = 0; i < names.length; i++) {
            GL41C.glProgramUniform1i(id, GL20C.glGetUniformLocation(id, names[i]), UNITS[i]);
        }
        inverseProjection = GL20C.glGetUniformLocation(id, "_flw_farInverseProjection");
        viewToWorld = GL20C.glGetUniformLocation(id, "_flw_farViewToWorld");
        mainRadius = GL20C.glGetUniformLocation(id, "_flw_farMainRadius");
        dhProjection = GL20C.glGetUniformLocation(id, "dhProjection");
        dhProjectionInverse = GL20C.glGetUniformLocation(id, "dhProjectionInverse");
    }

    /**
     * Native DH uniform setup precedes terrain; retain its view until the opaque-depth cull completes.
     */
    public void prepareLod(Matrix4fc projection, Matrix4fc modelView, int worldYOffset, float partialTicks) {
        lodProjection.set(projection);
        lodModelView.set(modelView);
        lodWorldYOffset = worldYOffset;
        lodPartialTick = partialTicks;
    }

    /**
     * Called after common culling has rendered this frame's retained surfaces against opaque terrain.
     */
    public static void afterOpaque() {
        if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline
                && pipeline.getDHCompat().getInstance() instanceof DHCompatInternal dh && dh.shouldOverride) {
            var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
            var far = manager == null ? null : manager.farVisuals();
            var surface = far == null ? null : far.surface();
            if (surface != null) {
                FarDhOwner owner = dh.getSolidShader();
                var program = owner.flywheel$far();
                program.draw(surface, program.lodProjection, program.lodModelView, program.lodWorldYOffset,
                        program.lodPartialTick, false);
            }
        }
    }

    /**
     * After opaque visualization: retained geometry within vanilla's radius also writes main scene depth.
     */
    public void drawMain(FarSurface surface, RenderContext context) {
        draw(surface, NativeDepth.active() ? context.projection()
                        : MatrixUtils.undoRevZ(new Matrix4f(context.projection())), context.modelView(), 0,
                context.partialTick(), true);
    }

    private void prepare(FarSurface surface, GlProgram compact) {
        if (prepared != surface) {
            int pixels = surface.width() * surface.height();
            if (capacity != pixels) {
                GL45C.glNamedBufferData(pixelBuffer, (long) pixels * Integer.BYTES, GL15C.GL_DYNAMIC_DRAW);
                GL45C.glTextureBuffer(pixelTexture, GL30C.GL_R32UI, pixelBuffer);
                capacity = pixels;
            }
            GL45C.glNamedBufferSubData(command, 0, new int[]{6, 0, 0, 0, 0});
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, command);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, pixelBuffer);
            GL45C.glBindTextureUnit(13, surface.depth());
            GL33C.glBindSampler(13, sampler);
            compact.bind();
            GL43C.glDispatchCompute((pixels + 255) / 256, 1, 1);
            GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
            prepared = surface;
        }
    }

    private void draw(FarSurface surface, Matrix4fc projection, Matrix4fc modelView, int worldYOffset,
                      float partialTicks, boolean main) {
        var compact = this.compact.get();
        if (compact == null) return;
        GlCompat.pushDebugGroup(main ? "far/dh/main" : "far/dh/lod");
        try (var saved = FarIrisState.capture(SAVED_UNITS, SAVED_BUFFER_UNITS, targets, 0, 1);
             var stack = MemoryStack.stackPush()) {
            prepare(surface, compact);
            GuestSsbos.restore(pipeline);
            if (main) {
                mainFramebuffer.bindAsDrawBuffer();
            } else {
                ((DHCompatInternal) pipeline.getDHCompat().getInstance()).getSolidFB().bindAsDrawBuffer();
            }
            GL11C.glViewport(0, 0, surface.width(), surface.height());
            GlStateManager._glUseProgram(id);
            shader.bind();
            shader.fillUniformData(projection, modelView, worldYOffset, partialTicks);
            if (main) {
                GL20C.glUniformMatrix4fv(dhProjection, false, projection.get(stack.mallocFloat(16)));
                GL20C.glUniformMatrix4fv(dhProjectionInverse, false,
                        new Matrix4f(projection).invert().get(stack.mallocFloat(16)));
            }
            GL20C.glUniform3f(shader.modelOffsetUniform, 0, 0, 0);
            GL20C.glUniform1f(shader.clipDistanceUniform, 0);
            GL20C.glUniform1f(mainRadius, main ? LodCompat.vanillaRadius() : 0);
            int[] textures = {surface.color(), surface.normal(), surface.light(), surface.depth(), pixelTexture,
                    surface.material()};
            for (int i = 0; i < textures.length; i++) {
                if (UNITS[i] < 12) {
                    GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + UNITS[i]);
                    GlStateManager._bindTexture(textures[i]);
                } else {
                    GL45C.glBindTextureUnit(UNITS[i], textures[i]);
                }
                GL33C.glBindSampler(UNITS[i], i == 4 ? 0 : sampler);
            }
            GL20C.glUniformMatrix4fv(inverseProjection, false, surface.inverseProjection().get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(viewToWorld, false, surface.viewToWorld().get(stack.mallocFloat(16)));
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(main || DhApi.Delayed.renderProxy.getDepthDirection().farDepth < 0.5f
                    ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
            GlStateManager._depthMask(true);
            for (int target = 0; target < targets; target++) GlStateManager._colorMask(target, 15);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, command);
            GL40C.glDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L);
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    public void delete() {
        shader.free();
        compact.close();
        GL15C.glDeleteBuffers(pixelBuffer);
        GL15C.glDeleteBuffers(command);
        GL15C.glDeleteBuffers(indexBuffer);
        GL11C.glDeleteTextures(pixelTexture);
        GL30C.glDeleteVertexArrays(vao);
        GL33C.glDeleteSamplers(sampler);
    }
}
