package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
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
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
import dev.engine_room.flywheel.backend.engine.indirect.WaveletOitChain;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Vec3i;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.*;

public class InstancedDrawManager extends DrawManager<InstancedInstancer<?>> {
    private static final Comparator<InstancedDraw> DRAW_COMPARATOR = Comparator.comparingInt(InstancedDraw::bias)
                                                                               .thenComparingInt(
                                                                                       InstancedDraw::indexOfMeshInModel)
                                                                               .thenComparing(InstancedDraw::material,
                                                                                       MaterialRenderState.COMPARATOR);

    private final List<InstancedDraw> allDraws = new ArrayList<>();
    private final List<InstancedDraw> draws = new ArrayList<>();
    private final List<InstancedDraw> oitDraws = new ArrayList<>();
    private final InstancingPrograms programs;
    /**
     * A map of vertex types to their mesh pools.
     */
    private final MeshPool meshPool;
    private final InstancedLight light;
    private final RenderPassUniforms renderPassUniforms = new RenderPassUniforms();
    private final WaveletOitChain oitChain = new WaveletOitChain();
    private final Matrix4f renderModelView = new Matrix4f();
    private boolean needSort = false;

    public InstancedDrawManager(InstancingPrograms programs) {
        programs.acquire();
        this.programs = programs;

        meshPool = new MeshPool();
        light = new InstancedLight();

    }

    @Override
    public void render(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelViewMatrix,
                       Vec3i renderOrigin, boolean constantAmbientLight) {
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

            for (var draw : allDraws) {
                if (draw.material()
                        .transparency() == Transparency.ORDER_INDEPENDENT) {
                    oitDraws.add(draw);
                } else {
                    draws.add(draw);
                }
            }

            needSort = false;
        }

        meshPool.flush();

        light.flush(lightStorage);

        if (draws.isEmpty()) {
            return;
        }

        submitDraws(modelViewMatrix);
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
        };
        return oitChain.render(renderModelView, meshPool.vertexBuffer(), meshPool.indexBuffer(),
                !oitDraws.isEmpty(), prePass, chunks, ber, terrain, fabulous, this::submitOitInstances);
    }

    // Opaque draw through Mojang RenderPass: encoder routing keeps 26.2's GL RHI state caches consistent
    // (createRenderPass resets lastPipeline; setPipeline re-applies it), fixing the raw-GL path's flash.
    private void submitDraws(Matrix4fc modelViewMatrix) {
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
                                            .writeTransform(new Matrix4f(modelViewMatrix));

        for (var drawCall : draws) {
            drawCall.instancer()
                    .prepareInstanceTexels();
        }

        TextureManager textureManager = mc.getTextureManager();
        GpuSampler lightOverlaySampler = RenderSystem.getSamplerCache()
                                                     .getClampToEdge(FilterMode.LINEAR);
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        GlCompat.pushDebugGroup("flywheel:gl/opaque");
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:instanced/opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.setIndexBuffer(indexBuffer, IndexType.INT);
            pass.bindTexture("Sampler1", overlayView, lightOverlaySampler);
            pass.bindTexture("Sampler2", lightmapView, lightOverlaySampler);
            bindLight(pass);

            for (var drawCall : draws) {
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
                pass.setPipeline(InstancingPipeline.pipelineFor(material, drawCall.groupKey.instanceType(), embedded));

                AbstractTexture atlas = textureManager.getTexture(material.texture());
                pass.bindTexture("Sampler0", atlas.getTextureView(), MaterialSamplers.get(material));

                pass.setUniform("_flw_instances", texels.slice());
                pass.setUniform("_FlwInstanceDraw",
                        renderPassUniforms.material(MaterialEncoder.packProperties(material)));
                if (embedded) {
                    EmbeddedEnvironment env = (EmbeddedEnvironment) environment;
                    pass.setUniform("_FlwEmbed", renderPassUniforms.embed(env.pose(), env.normal()));
                }
                pass.drawIndexed(mesh.indexCount(), live, mesh.firstIndex(), mesh.baseVertex(), 0);
            }
        }
        GlCompat.popDebugGroup();
    }

    private void submitOitInstances(RenderPass pass, OitMode mode, OitFrame f) {
        boolean needsColor = mode != OitMode.DEPTH_RANGE;
        if (needsColor) {
            bindLight(pass);
        }

        for (var drawCall : oitDraws) {
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
            pass.setPipeline(OitPipelines.producer(material, drawCall.groupKey.instanceType(), mode, false, embedded));

            if (needsColor) {
                AbstractTexture atlas = f.textureManager()
                                         .getTexture(material.texture());
                pass.bindTexture("Sampler0", atlas.getTextureView(), MaterialSamplers.get(material));
            }

            pass.setUniform("_flw_instances", texels.slice());
            pass.setUniform("_FlwInstanceDraw", renderPassUniforms.material(MaterialEncoder.packProperties(material)));
            if (embedded) {
                EmbeddedEnvironment env = (EmbeddedEnvironment) environment;
                pass.setUniform("_FlwEmbed", renderPassUniforms.embed(env.pose(), env.normal()));
            }
            pass.drawIndexed(mesh.indexCount(), live, mesh.firstIndex(), mesh.baseVertex(), 0);
        }
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
            InstancedDraw instancedDraw = new InstancedDraw(instancer, mesh, groupKey, entry.material(), key.bias(), i);

            allDraws.add(instancedDraw);
            needSort = true;
            instancer.addDrawCall(instancedDraw);
            warmUp(entry.material(), key.type());
        }
    }

    private void warmUp(Material material, InstanceType<?> type) {
        if (material.transparency() == Transparency.ORDER_INDEPENDENT) {
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
                            pass.setPipeline(CrumblingPipelines.pipeline(crumblingMaterial, instanceType, false));

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
}
