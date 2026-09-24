package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
import dev.engine_room.flywheel.backend.engine.indirect.WaveletOitChain;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;

import java.util.*;

public class InstancedDrawManager extends DrawManager<InstancedInstancer<?>> {
    private static final Comparator<InstancedDraw> DRAW_COMPARATOR = Comparator.comparingInt(InstancedDraw::bias)
                                                                               .thenComparingInt(
                                                                                       InstancedDraw::indexOfMeshInModel)
                                                                               .thenComparing(InstancedDraw::material,
                                                                                       MaterialRenderState.COMPARATOR);
    private final List<InstancedDraw> allDraws = new ArrayList<>();
    protected final List<InstancedDraw> draws = new ArrayList<>();
    protected final List<InstancedDraw> oitDraws = new ArrayList<>();
    protected final List<InstancedDraw> oitAdditiveDraws = new ArrayList<>();
    private final InstancingPrograms programs;
    /**
     * A map of vertex types to their mesh pools.
     */
    private final MeshPool meshPool;
    private final InstancedLight light;
    private final RenderPassUniforms renderPassUniforms = new RenderPassUniforms();
    private final WaveletOitChain oitChain = new WaveletOitChain();
    protected final Matrix4f renderModelView = new Matrix4f();
    private boolean needSort = false;
    private boolean hasLineDraws;

    public InstancedDrawManager(InstancingPrograms programs) {
        programs.acquire();
        this.programs = programs;

        meshPool = new MeshPool();
        light = new InstancedLight();
    }

    private static boolean lineMaterial(Material material) {
        return material.shaders().vertexSource().equals(StandardMaterialShaders.LINE.vertexSource());
    }

    private static void bindUbo(Map<String, Uniform> uniforms, String name, GpuBufferSlice slice) {
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, ((Uniform.Ubo) uniforms.get(name)).blockBinding(),
                ((GlBuffer) slice.buffer()).handle(), slice.offset(), slice.length());
    }

    @Override
    public void render(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelViewMatrix,
                       Vec3i renderOrigin, boolean constantAmbientLight) {
        prepare(lightStorage, environmentStorage, modelViewMatrix, renderOrigin, constantAmbientLight);
        submitOpaque();
    }

    /**
     * The frame's instance/mesh/light uploads, before any pass.
     */
    protected void prepare(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                           Matrix4fc modelViewMatrix, Vec3i renderOrigin, boolean constantAmbientLight) {
        super.render(lightStorage, environmentStorage, modelViewMatrix, renderOrigin, constantAmbientLight);

        renderModelView.set(modelViewMatrix);
        renderPassUniforms.beginFrame(renderOrigin, constantAmbientLight);

        this.instancers.values()
                       .removeIf(instancer -> {
                           if (instancer.instanceCount() == 0) {
                               instancer.delete();
                               return true;
                           } else {
                               instancer.updateBuffer();
                               instancer.resetTexelsReady();
                               return false;
                           }
                       });

        // Remove the draw calls for any instancers we deleted.
        needSort |= allDraws.removeIf(InstancedDraw::deleted);

        if (needSort) {
            allDraws.sort(DRAW_COMPARATOR);

            draws.clear();
            oitDraws.clear();
            oitAdditiveDraws.clear();
            hasLineDraws = false;

            for (var draw : allDraws) {
                if (lineMaterial(draw.material())) hasLineDraws = true;
                if (drawnInAdditivePass(draw)) {
                    oitAdditiveDraws.add(draw);
                } else if (drawnInTranslucentPass(draw)) {
                    oitDraws.add(draw);
                } else {
                    draws.add(draw);
                }
            }

            needSort = false;
        }

        if (hasLineDraws) renderPassUniforms.lineFrameSlice();

        meshPool.flush();

        light.flush(lightStorage);
    }

    protected void submitOpaque() {
        if (draws.isEmpty()) {
            return;
        }

        GlCompat.pushDebugGroup("flywheel:gl/opaque");
        submitPass("flywheel:instanced/opaque", draws, renderModelView, this::pipelineFor);
        GlCompat.popDebugGroup();
    }

    protected boolean drawnInTranslucentPass(InstancedDraw draw) {
        return OitTransparency.orderIndependent(draw.material());
    }

    protected boolean drawnInAdditivePass(InstancedDraw draw) {
        return OitTransparency.additive(draw.material());
    }

    protected RenderPipeline pipelineFor(Material material, InstanceType<?> type, boolean embedded) {
        return InstancingPipeline.pipelineFor(material, type, embedded);
    }

    protected RenderPipeline crumblingPipelineFor(Material crumblingMaterial, InstanceType<?> type) {
        return CrumblingPipelines.pipeline(crumblingMaterial, type, false);
    }

    @Override
    public boolean renderOit(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                             @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                             @Nullable SodiumTerrainOitReplay terrain, @Nullable FabulousCaptures fabulous) {
        if (BackendDebugFlags.SKIP_OIT) {
            return false;
        }
        Runnable prePass = () -> {
            for (var drawCall : oitDraws) {
                drawCall.instancer()
                        .prepareInstanceTexels();
            }
            for (var drawCall : oitAdditiveDraws) {
                drawCall.instancer()
                        .prepareInstanceTexels();
            }
        };
        return oitChain.render(renderModelView, meshPool.vertexBuffer(), meshPool.indexBuffer(),
                !oitDraws.isEmpty() || !oitAdditiveDraws.isEmpty(), !oitAdditiveDraws.isEmpty(), prePass, chunks, ber,
                terrain, fabulous, this::submitOitInstances);
    }

    // Opaque draw through Mojang RenderPass: encoder routing keeps 26.2's GL RHI state caches consistent
    // (createRenderPass resets lastPipeline; setPipeline re-applies it), fixing the raw-GL path's flash.
    protected void submitPass(String label, List<InstancedDraw> list, Matrix4fc modelView,
                              PipelineSelector pipelineFor) {
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice()
                                             .createCommandEncoder();
        var dynamicTransforms = RenderSystem.getDynamicUniforms()
                                            .writeTransform(new Matrix4f(modelView));

        for (var drawCall : list) {
            drawCall.instancer()
                    .prepareInstanceTexels();
        }

        TextureManager textureManager = mc.getTextureManager();
        GpuSampler lightOverlaySampler = RenderSystem.getSamplerCache()
                                                     .getClampToEdge(FilterMode.LINEAR);
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        try (RenderPass pass = encoder.createRenderPass(() -> label,
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            meshPool.bindExtras(pass);
            pass.setIndexBuffer(indexBuffer, IndexType.INT);
            pass.bindTexture("Sampler1", overlayView, lightOverlaySampler);
            pass.bindTexture("Sampler2", lightmapView, lightOverlaySampler);
            bindLight(pass);

            drawRuns(pass, list, pipelineFor, textureManager);
        }
    }

    // Port: RenderPass.drawIndexed re-runs trySetup (every sampler, texel buffer, UBO) per draw, ~12 us on 1300-draw
    // entity scenes. Set up once per pipeline/texture run, then bind only per-draw state and draw raw.
    private void drawRuns(RenderPass pass, List<InstancedDraw> list, PipelineSelector pipelineFor,
                          @Nullable TextureManager textureManager) {
        RenderPipeline lastPipeline = null;
        Identifier lastTexture = null;
        GpuSampler lastSampler = null;
        Map<String, Uniform> uniforms = Map.of();
        for (var drawCall : list) {
            var mesh = drawCall.mesh();
            if (mesh.isInvalid()) {
                continue;
            }
            InstancedInstancer<?> instancer = drawCall.instancer();
            int live = instancer.instanceCount();
            GpuBuffer texels = instancer.instanceTexels();
            if (live == 0 || texels == null) {
                continue;
            }

            Material material = drawCall.material();
            var environment = drawCall.groupKey.environment();
            boolean embedded = environment instanceof EmbeddedEnvironment;
            RenderPipeline pipeline = pipelineFor.pipeline(material, drawCall.groupKey.instanceType(), embedded);
            GpuBufferSlice materialSlice = renderPassUniforms.material(MaterialEncoder.packProperties(material),
                    drawCall.tags());
            GpuBufferSlice embedSlice = null;
            if (embedded) {
                EmbeddedEnvironment env = (EmbeddedEnvironment) environment;
                embedSlice = renderPassUniforms.embed(env.pose(), env.normal());
            }

            boolean prime = false;
            if (pipeline != lastPipeline) {
                pass.setPipeline(pipeline);
                lastPipeline = pipeline;
                prime = true;
            }
            if (textureManager != null) {
                GpuSampler sampler = MaterialSamplers.get(material);
                if (!material.texture().equals(lastTexture) || sampler != lastSampler) {
                    lastTexture = material.texture();
                    lastSampler = sampler;
                    pass.bindTexture("Sampler0", textureManager.getTexture(lastTexture).getTextureView(), sampler);
                    prime = true;
                }
            }
            if (prime) {
                pass.setUniform("_flw_instances", texels.slice());
                pass.setUniform("_FlwInstanceDraw", materialSlice);
                if (lineMaterial(material))
                    pass.setUniform("_FlwLineFrameUniforms", renderPassUniforms.lineFrameSlice());
                if (embedSlice != null) {
                    pass.setUniform("_FlwEmbed", embedSlice);
                }
                pass.drawIndexed(0, 0, 0, 0, 0);
                uniforms = ((GlRenderPipeline) RenderSystem.getDevice().precompilePipeline(pipeline)).program()
                                                                                                     .getUniforms();
            }

            GlStateManager._activeTexture(
                    GL13C.GL_TEXTURE0 + ((Uniform.Utb) uniforms.get("_flw_instances")).samplerIndex());
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, instancer.texelTexture());
            bindUbo(uniforms, "_FlwInstanceDraw", materialSlice);
            if (embedSlice != null) {
                bindUbo(uniforms, "_FlwEmbed", embedSlice);
            }
            GL32C.glDrawElementsInstancedBaseVertex(GL11C.GL_TRIANGLES, mesh.indexCount(), GL11C.GL_UNSIGNED_INT,
                    (long) mesh.firstIndex() * Integer.BYTES, live, mesh.baseVertex());
        }
    }

    private void submitOitInstances(RenderPass pass, OitMode mode, OitFrame f, boolean additive) {
        boolean needsColor = mode != OitMode.DEPTH_RANGE;
        if (needsColor) {
            bindLight(pass);
        } else {
            pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
        }

        drawRuns(pass, additive ? oitAdditiveDraws : oitDraws,
                (material, type, embedded) -> OitPipelines.producer(material, type, mode, false, embedded),
                needsColor ? f.textureManager() : null);
    }

    private void bindLight(RenderPass pass) {
        pass.setUniform("_flw_lightLut", light.lutBuffer()
                                              .slice());
        pass.setUniform("_flw_lightSections", light.sectionsBuffer()
                                                   .slice());
        pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
    }

    @Override
    public void delete() {
        instancers.values()
                  .forEach(InstancedInstancer::delete);

        allDraws.forEach(InstancedDraw::delete);
        allDraws.clear();
        draws.clear();
        oitDraws.clear();
        oitAdditiveDraws.clear();

        meshPool.delete();
        programs.release();

        light.delete();
        renderPassUniforms.delete();

        oitChain.delete();

        super.delete();
    }

    @Override
    protected <I extends Instance> InstancedInstancer<I> create(InstancerKey<I> key) {
        return new InstancedInstancer<>(key, new AbstractInstancer.Recreate<>(key, this));
    }

    @Override
    protected <I extends Instance> void initialize(InstancerKey<I> key, InstancedInstancer<?> instancer) {
        instancer.init();

        var meshes = key.model()
                        .meshes();
        for (int i = 0; i < meshes.size(); i++) {
            var entry = meshes.get(i);
            var mesh = meshPool.alloc(entry.mesh());

            GroupKey<?> groupKey = new GroupKey<>(key.type(), key.environment());
            InstancedDraw instancedDraw = new InstancedDraw(instancer, mesh, groupKey, entry.material(), key.bias(), i,
                    drawTags(key.environment(), key.model(), entry.material(), entry.mesh()));

            allDraws.add(instancedDraw);
            needSort = true;
            instancer.addDrawCall(instancedDraw);
            warmUp(entry.material(), key.type());
        }
    }

    /**
     * Shaderpack guest tags of a new draw; {@code null} natively.
     */
    protected @Nullable DrawTags drawTags(Environment environment, Model model, Material material, Mesh mesh) {
        return null;
    }

    protected void warmUp(Material material, InstanceType<?> type) {
        if (OitTransparency.additive(material)) {
            OitPipelines.producer(material, type, OitMode.EVALUATE);
        } else if (OitTransparency.orderIndependent(material)) {
            OitPipelines.producer(material, type, OitMode.DEPTH_RANGE);
            OitPipelines.producer(material, type, OitMode.GENERATE_COEFFICIENTS);
            OitPipelines.producer(material, type, OitMode.EVALUATE);
        } else {
            InstancingPipeline.pipelineFor(material, type);
        }
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
        var byType = doCrumblingSort(crumblingBlocks,
                handle -> handle instanceof InstancedInstancer<?> instancer ? instancer : null);

        if (byType.isEmpty()) {
            return;
        }

        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        for (var byProgress : byType.values()) {
            for (var pairs : byProgress.values()) {
                for (var pair : pairs) {
                    pair.getFirst()
                        .prepareInstanceTexels();
                }
            }
        }

        CommandEncoder encoder = RenderSystem.getDevice()
                                             .createCommandEncoder();
        var dynamicTransforms = RenderSystem.getDynamicUniforms()
                                            .writeTransform(new Matrix4f(renderModelView));

        TextureManager textureManager = mc.getTextureManager();
        GpuSampler atlasSampler = RenderSystem.getSamplerCache()
                                              .getClampToEdge(FilterMode.LINEAR, true);
        GpuSampler lightOverlaySampler = RenderSystem.getSamplerCache()
                                                     .getClampToEdge(FilterMode.LINEAR);
        GpuSampler crackSampler = RenderSystem.getSamplerCache()
                                              .getRepeat(FilterMode.NEAREST);
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        var crumblingMaterial = SimpleMaterial.builder();

        GlCompat.pushDebugGroup("flywheel:gl/crumbling");
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:instanced/crumbling",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            meshPool.bindExtras(pass);
            pass.setIndexBuffer(indexBuffer, IndexType.INT);
            pass.bindTexture("Sampler1", overlayView, lightOverlaySampler);
            pass.bindTexture("Sampler2", lightmapView, lightOverlaySampler);
            bindLight(pass);

            for (var groupEntry : byType.entrySet()) {
                InstanceType<?> instanceType = groupEntry.getKey()
                                                         .instanceType();

                for (var progressEntry : groupEntry.getValue()
                                                   .int2ObjectEntrySet()) {
                    GpuTextureView crackView = textureManager.getTexture(
                                                                     ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey()))
                                                             .getTextureView();
                    pass.bindTexture("_flw_crumblingTex", crackView, crackSampler);

                    for (var pair : progressEntry.getValue()) {
                        InstancedInstancer<?> instancer = pair.getFirst();
                        int index = pair.getSecond().index;
                        GpuBuffer texels = instancer.instanceTexels();
                        if (texels == null) {
                            continue;
                        }

                        for (InstancedDraw draw : instancer.draws()) {
                            var mesh = draw.mesh();
                            if (mesh.isInvalid()) {
                                continue;
                            }
                            CommonCrumbling.applyCrumblingProperties(crumblingMaterial, draw.material());
                            pass.setPipeline(crumblingPipelineFor(crumblingMaterial, instanceType));

                            AbstractTexture atlas = textureManager.getTexture(draw.material()
                                                                                  .texture());
                            pass.bindTexture("Sampler0", atlas.getTextureView(), atlasSampler);
                            pass.setUniform("_flw_instances", texels.slice());
                            pass.setUniform("_FlwInstanceDraw",
                                    renderPassUniforms.material(MaterialEncoder.packProperties(crumblingMaterial)));

                            pass.drawIndexed(mesh.indexCount(), 1, mesh.firstIndex(), mesh.baseVertex(), index);
                        }
                    }
                }
            }
        }
        GlCompat.popDebugGroup();
    }

    @Override
    public void triggerFallback() {
        InstancingPrograms.kill();
        // 26.2: allChanged() moved to LevelExtractor (it rebuilds the SectionUpdateTracker the bare
        // invalidateCompiledGeometry defers to does NOT). Not reentrant: runs inside the draw loop.
        Minecraft mc = Minecraft.getInstance();
        mc.levelExtractor.allChanged();
    }

    @Override
    public MeshPool meshPool() {
        return meshPool;
    }

    @FunctionalInterface
    public interface PipelineSelector {
        RenderPipeline pipeline(Material material, InstanceType<?> type, boolean embedded);
    }
}
