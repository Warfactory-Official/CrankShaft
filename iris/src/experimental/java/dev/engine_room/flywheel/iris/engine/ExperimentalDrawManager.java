package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture;
import dev.engine_room.flywheel.backend.engine.CommonCrumbling;
import dev.engine_room.flywheel.backend.engine.DrawManager;
import dev.engine_room.flywheel.backend.engine.GroupKey;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.RenderPassUniforms;
import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.iris.compile.ExperimentalOit;
import dev.engine_room.flywheel.iris.compile.ExperimentalPipelines;
import dev.engine_room.flywheel.iris.compile.OitPass;
import dev.engine_room.flywheel.iris.compile.PackRole;
import dev.engine_room.flywheel.iris.experimental.IndirectCommands;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.IrisRenderPass;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.WorldRenderTargets.Phase;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ExperimentalDrawManager extends DrawManager<ExperimentalInstancer<?>> {
    private final MeshPool meshes = new MeshPool();
    private final GuestVertexExtras extras = new GuestVertexExtras();
    private final RenderPassUniforms uniforms = new RenderPassUniforms();
    private final ExperimentalCulling culling = new ExperimentalCulling();
    private final ExperimentalInstanceArena arena = new ExperimentalInstanceArena();
    private final List<InstancedDraw> draws = new ArrayList<>();
    private final Matrix4f mainView = new Matrix4f();
    private final Map<Material, Material> crumblingMaterials = new HashMap<>();
    private final boolean indirect;
    private IrisRenderingPipeline owner;
    private boolean deferredEmissive;
    private boolean nativeGlint;
    private GpuBuffer lightSections;
    private GpuBuffer lightLut;
    private GpuBuffer matrices;
    private boolean dirty;
    private boolean hasOrderIndependent;

    public ExperimentalDrawManager(boolean indirect) {
        this.indirect = indirect;
        updatePack();
        meshes.extras(extras);
    }

    private static boolean emissive(Material material) {
        return material.transparency() == Transparency.ADDITIVE || material.transparency() == Transparency.LIGHTNING
                || material.transparency() == Transparency.ORDER_INDEPENDENT_ADDITIVE;
    }

    private void updatePack() {
        var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        if (owner == pipeline) return;
        owner = pipeline;
        deferredEmissive = ((IrisRenderingPipelineAccessor) pipeline).flywheel$pack().flywheel$deferredEmissive();
        var programs = ((ProgramFallbackResolverAccessor) ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver()).flywheel$programs();
        nativeGlint = programs.flywheel$nativeGlint();
    }

    @Override
    protected <I extends Instance> ExperimentalInstancer<I> create(InstancerKey<I> key) {
        return new ExperimentalInstancer<>(key, new AbstractInstancer.Recreate<>(key, this), arena);
    }

    @Override
    protected <I extends Instance> void initialize(InstancerKey<I> key, ExperimentalInstancer<?> instancer) {
        var model = key.model();
        for (int i = 0; i < model.meshes().size(); i++) {
            var entry = model.meshes().get(i);
            Minecraft.getInstance().getTextureManager().getTexture(entry.material().texture());
            var draw = new InstancedDraw(instancer, meshes.alloc(entry.mesh()),
                    new GroupKey<>(key.type(), key.environment()),
                    entry.material(), key.bias(), i,
                    GuestDrawTags.of(key.environment(), model, entry.material(), entry.mesh()));
            draws.add(draw);
            instancer.addDrawCall(draw);
        }
        dirty = true;
    }

    @Override
    public void render(LightStorage light, EnvironmentStorage environments, Matrix4fc view, Vec3i origin,
                       boolean ambient) {
        prepare(light, environments, view, origin, ambient);
        draw(view, false, false, true, true);
    }

    void prepare(LightStorage light, EnvironmentStorage environments, Matrix4fc view, Vec3i origin, boolean ambient) {
        updatePack();
        super.render(light, environments, view, origin, ambient);
        mainView.set(view);
        uniforms.beginFrame(origin, ambient);
        instancers.values().removeIf(instancer -> {
            if (instancer.instanceCount() == 0) {
                culling.remove(instancer);
                instancer.delete();
                return true;
            }
            instancer.updateBuffer();
            if (!indirect) instancer.range(0);
            return false;
        });
        dirty |= draws.removeIf(InstancedDraw::deleted);
        if (dirty) {
            draws.sort(Comparator.comparingInt(InstancedDraw::bias).thenComparingInt(InstancedDraw::indexOfMeshInModel)
                                 .thenComparing(InstancedDraw::material, MaterialRenderState.COMPARATOR));
            dirty = false;
            hasOrderIndependent = draws.stream().anyMatch(draw -> GuestDrawManager.orderIndependent(draw.material(),
                    draw.tags() == null ? 0 : draw.tags().drawTag()));
        }
        if (extras.blockIdsChanged()) meshes.invalidateExtras();
        meshes.flush();
        if (indirect) {
            long bytes = environments.arena.byteCapacity();
            if (matrices == null || matrices.size() < bytes) {
                if (matrices != null) Iris.getRenderBackend().retire(matrices::close);
                matrices = Iris.getRenderBackend()
                               .createStorageBuffer("flywheel guest environment matrices", bytes, null);
            }
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(matrices.slice(0, bytes),
                    MemoryUtil.memByteBuffer(environments.arena.indexToPointer(0), Math.toIntExact(bytes)));
        }
        uploadLight(light);
    }

    private void uploadLight(LightStorage light) {
        int bytes = Math.max(4, light.sectionDataBytes());
        boolean grew = lightSections == null || lightSections.size() < bytes;
        if (grew) {
            if (lightSections != null) Iris.getRenderBackend().retire(lightSections::close);
            lightSections = Iris.getRenderBackend().createStorageBuffer("flywheel guest light sections", bytes, null);
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (light.sectionDataBytes() > 0 && (grew || light.hasSectionChanges())) {
            encoder.writeToBuffer(lightSections.slice(0, light.sectionDataBytes()),
                    MemoryUtil.memByteBuffer(light.sectionDataPointer(), light.sectionDataBytes()));
            light.clearSectionChanges();
        } else if (grew) {
            encoder.writeToBuffer(lightSections.slice(), ByteBuffer.allocateDirect(bytes));
        }
        boolean lutChanged = light.checkNeedsLutRebuildAndClear();
        if (lightLut == null || lutChanged) {
            var lut = light.createLut();
            ByteBuffer data = ByteBuffer.allocateDirect(Math.max(4, lut.size() * 4)).order(ByteOrder.nativeOrder());
            for (int i = 0; i < lut.size(); i++) data.putInt(lut.getInt(i));
            data.position(0);
            if (lightLut != null) Iris.getRenderBackend().retire(lightLut::close);
            lightLut = Iris.getRenderBackend()
                           .createStorageBuffer("flywheel guest light lookup", data.remaining(), null);
            encoder.writeToBuffer(lightLut.slice(), data);
        }
    }

    private boolean prepareDraw(Matrix4fc view, boolean shadow) {
        if (draws.isEmpty() || meshes.vertexBuffer() == null) return false;
        Matrix4fc projection = shadow ? ShadowRenderer.PROJECTION
                : CapturedRenderingState.INSTANCE.getGbufferProjection();
        if (indirect) {
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);
            // Compat with Iris: native compute dispatch publishes its storage and indirect writes.
            culling.cull(instancers.values(), draws, viewProjection);
        }
        return true;
    }

    void draw(Matrix4fc view, boolean shadow, boolean translucent, boolean entities, boolean blockEntities) {
        if (translucent) renderTranslucent(view, shadow, entities, blockEntities, null);
        else if (prepareDraw(view, shadow)) record(view, shadow, false, entities, blockEntities, null, 0);
    }

    private boolean renderTranslucent(Matrix4fc view, boolean shadow, boolean entities, boolean blockEntities,
                                      ExperimentalTerrainDispatcher terrain) {
        boolean visuals = prepareDraw(view, shadow);
        if (!visuals && terrain == null) return false;
        if (!hasOrderIndependent && terrain == null) {
            if (visuals) record(view, shadow, true, entities, blockEntities, null, 0);
            return false;
        }
        var parent = (IrisRenderingPipeline) Iris.getPipelineManager()
                                                 .getPipelineNullable();
        var oit = ExperimentalOit.get(parent, shadow);
        if (oit == null) {
            if (visuals) record(view, shadow, true, entities, blockEntities, null, 0);
            return terrain != null && terrain.drawCaptured();
        }
        if (visuals) record(view, shadow, true, entities, blockEntities, null, 1);
        oit.prepare();
        for (var pass : OitPass.values()) {
            if (pass == OitPass.COEFFICIENTS && !oit.hasCoefficients()) continue;
            if (visuals) record(view, shadow, true, entities, blockEntities, pass, 2);
            if (terrain != null) terrain.drawWavelet(pass);
        }
        oit.composite();
        return terrain != null;
    }

    private void record(Matrix4fc view, boolean shadow, boolean translucent, boolean entities, boolean blockEntities,
                        OitPass oitPass, int selection) {
        Phase phase = shadow ? Phase.SHADOW : translucent ? Phase.TRANSLUCENT : Phase.OPAQUE;
        // Prepare programs, resources and targets before opening the pass.
        for (var draw : draws) {
            if (accept(draw, shadow, translucent, entities, blockEntities, selection))
                program(draw, shadow, phase, oitPass);
        }
        Map<String, List<GpuBufferSlice>> buffers = commonBuffers(view);
        buffers.put("InstanceBuffer", List.of(arena.buffer().slice()));
        if (indirect) {
            buffers.put("TargetBuffer", List.of(culling.visible().slice()));
            buffers.put("DrawBuffer", List.of(culling.commands().slice()));
            buffers.put("MatrixBuffer", List.of(matrices.slice()));
        }
        IrisRenderPass pass = null;
        ExperimentalPipelines.Program active = null;
        try {
            for (int index = 0; index < draws.size(); ) {
                var draw = draws.get(index);
                if (!accept(draw, shadow, translucent, entities, blockEntities, selection)) {
                    index++;
                    continue;
                }
                var program = program(draw, shadow, phase, oitPass);
                int end = index + 1;
                if (indirect)
                    while (end < draws.size() && accept(draws.get(end), shadow, translucent, entities, blockEntities,
                            selection)
                            && program(draws.get(end), shadow, phase, oitPass) == program
                            && MaterialRenderState.COMPARATOR.compare(draw.material(), draws.get(end).material()) == 0)
                        end++;
                if (program != active) {
                    if (pass != null) pass.close();
                    pass = program.open();
                    active = program;
                    pass.setVertexBuffer(0, meshes.vertexBuffer().slice());
                    pass.setVertexBuffer(1, meshes.extrasBuffer().slice());
                    pass.setIndexBuffer(meshes.indexBuffer(), IndexType.INT);
                }
                var instancer = (ExperimentalInstancer<?>) draw.instancer();
                if (indirect) buffers.put("_FlwEmbedDraw", List.of(uniforms.embedDraw(index)));
                else buffers.put("_FlwGuestInstanceRange", List.of(instancer.range));
                buffers.put("_FlwInstanceDraw",
                        List.of(uniforms.material(MaterialEncoder.packProperties(draw.material()),
                                indirect ? null : draw.tags())));
                if (!indirect && instancer.environment instanceof EmbeddedEnvironment embedded) {
                    buffers.put("_FlwEmbed", List.of(uniforms.embed(embedded.pose(), embedded.normal())));
                }
                Map<String, Long> texels = Map.of();
                var texture = Minecraft.getInstance().getTextureManager().getTexture(draw.material().texture())
                                       .getTextureView();
                program.bind(pass, buffers, texels, texture, MaterialSamplers.get(draw.material()));
                if (indirect) ((IndirectCommands) pass).flywheel$drawIndexedIndirect(culling.commands().slice(
                                (long) index * ExperimentalCulling.COMMAND_BYTES,
                                (long) (end - index) * ExperimentalCulling.COMMAND_BYTES),
                        end - index, ExperimentalCulling.COMMAND_BYTES);
                else pass.drawIndexed(draw.mesh().indexCount(), instancer.instanceCount(), draw.mesh().firstIndex(),
                        draw.mesh().baseVertex(), 0);
                index = end;
            }
        } finally {
            if (pass != null) pass.close();
        }
    }

    private Map<String, List<GpuBufferSlice>> commonBuffers(Matrix4fc view) {
        Map<String, List<GpuBufferSlice>> buffers = new HashMap<>();
        buffers.put("iris_DynamicTransforms",
                List.of(RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view))));
        buffers.put("iris_Projection", List.of(RenderSystem.getProjectionMatrixBuffer()));
        buffers.put("iris_Fog", List.of(RenderSystem.getShaderFog()));
        buffers.put("iris_Globals", List.of(RenderSystem.getGlobalSettingsUniform().slice()));
        buffers.put("iris_Lighting", List.of(RenderSystem.getShaderLights()));
        buffers.put("Lighting", List.of(RenderSystem.getShaderLights()));
        buffers.put("_FlwLineFrameUniforms", List.of(uniforms.lineFrameSlice()));
        buffers.put("_FlwRenderOrigin", List.of(uniforms.renderOriginSlice()));
        buffers.put("LightLut", List.of(lightLut.slice()));
        buffers.put("LightSections", List.of(lightSections.slice()));
        return buffers;
    }

    private ExperimentalPipelines.Program program(InstancedDraw draw, boolean shadow, Phase phase, OitPass oitPass) {
        int tag = draw.tags() == null ? 0 : draw.tags().drawTag();
        PackRole role = shadow ? PackRole.SHADOW : emissive(draw.material()) ? PackRole.ADDITIVE
                : translucent(draw) ? PackRole.TRANSLUCENT : PackRole.SOLID;
        return ExperimentalPipelines.get(role.forKind(TaggedEnvironment.kind(tag)), draw.material(),
                draw.groupKey.instanceType(),
                draw.instancer().environment instanceof EmbeddedEnvironment, indirect, phase, oitPass);
    }

    private boolean accept(InstancedDraw draw, boolean shadow, boolean translucent, boolean entities,
                           boolean blockEntities, int selection) {
        if (draw.mesh().isInvalid() || draw.instancer().instanceCount() == 0) return false;
        int kind = TaggedEnvironment.kind(draw.tags() == null ? 0 : draw.tags().drawTag());
        if (shadow && ((!entities && TaggedEnvironment.isEntity(draw.tags() == null ? 0 : draw.tags().drawTag()))
                || !blockEntities && kind == TaggedEnvironment.KIND_BLOCK_ENTITY || emissive(draw.material())
                || draw.material().transparency() == Transparency.GLINT)) return false;
        boolean orderIndependent = GuestDrawManager.orderIndependent(draw.material(),
                draw.tags() == null ? 0 : draw.tags().drawTag());
        if (selection == 1 && orderIndependent || selection == 2 && !orderIndependent) return false;
        return translucent(draw) == translucent;
    }

    private boolean translucent(InstancedDraw draw) {
        Transparency transparency = draw.material().transparency();
        int kind = TaggedEnvironment.kind(draw.tags() == null ? 0 : draw.tags().drawTag());
        if (emissive(draw.material())) return !deferredEmissive;
        return OitTransparency.orderIndependent(draw.material())
                || transparency == Transparency.TRANSLUCENT || transparency == Transparency.TRANSLUCENT_ALPHA_REPLACE
                || transparency == Transparency.GLINT && nativeGlint || kind == TaggedEnvironment.KIND_ENTITY_TRANSLUCENT
                || kind == TaggedEnvironment.KIND_ENTITY_BLENDED;
    }

    @Override
    public boolean renderOit(LightStorage light, EnvironmentStorage environments, ChunkSectionsToRender chunks,
                             BerTranslucentCapture ber, SodiumTerrainOitReplay terrain) {
        return renderTranslucent(mainView, false, true, true,
                terrain instanceof ExperimentalTerrainDispatcher captured ? captured : null);
    }

    @Override
    public boolean isShaderPackGuest() {
        return true;
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> blocks) {
        if (meshes.vertexBuffer() == null) return;
        var groups = doCrumblingSort(blocks,
                state -> state instanceof ExperimentalInstancer<?> instancer ? instancer : null);
        Map<String, List<GpuBufferSlice>> buffers = commonBuffers(mainView);
        for (var progressGroups : groups.values()) {
            for (var progress : progressGroups.int2ObjectEntrySet()) {
                var crack = Minecraft.getInstance().getTextureManager().getTexture(
                        Identifier.withDefaultNamespace("textures/block/destroy_stage_"
                                + progress.getIntKey() + ".png")).getTextureView();
                for (var pair : progress.getValue()) {
                    var instancer = pair.getFirst();
                    instancer.updateBuffer();
                    for (var draw : instancer.draws()) {
                        if (draw.mesh().isInvalid()) continue;
                        Material material = crumblingMaterials.computeIfAbsent(draw.material(), base -> {
                            var builder = SimpleMaterial.builder();
                            CommonCrumbling.applyCrumblingProperties(builder, base);
                            return builder.build();
                        });
                        var program = ExperimentalPipelines.get(PackRole.DAMAGED, material, instancer.type,
                                instancer.environment instanceof EmbeddedEnvironment, false, Phase.OPAQUE);
                        buffers.put("InstanceBuffer", List.of(instancer.instanceTexels().slice()));
                        instancer.range(0);
                        buffers.put("_FlwGuestInstanceRange", List.of(instancer.range));
                        buffers.put("_FlwInstanceDraw",
                                List.of(uniforms.material(MaterialEncoder.packProperties(material), draw.tags())));
                        if (instancer.environment instanceof EmbeddedEnvironment embedded) {
                            buffers.put("_FlwEmbed", List.of(uniforms.embed(embedded.pose(), embedded.normal())));
                        }
                        program.crumblingTexture(crack);
                        try (var pass = program.open()) {
                            pass.setVertexBuffer(0, meshes.vertexBuffer().slice());
                            pass.setVertexBuffer(1, meshes.extrasBuffer().slice());
                            pass.setIndexBuffer(meshes.indexBuffer(), IndexType.INT);
                            // Pack damaged-block programs sample the crack as their primary albedo.
                            program.bind(pass, buffers, Map.of(), crack, RenderSystem.getSamplerCache()
                                                                                     .getRepeat(
                                                                                             FilterMode.NEAREST));
                            pass.drawIndexed(draw.mesh().indexCount(), 1, draw.mesh().firstIndex(),
                                    draw.mesh().baseVertex(),
                                    pair.getSecond().index);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void triggerFallback() {
        BackendManagerImpl.reselect();
    }

    @Override
    public MeshPool meshPool() {
        return meshes;
    }

    @Override
    public void delete() {
        instancers.values().forEach(ExperimentalInstancer::delete);
        draws.clear();
        culling.close();
        arena.close();
        uniforms.delete();
        meshes.delete();
        if (lightSections != null) Iris.getRenderBackend().retire(lightSections::close);
        if (lightLut != null) Iris.getRenderBackend().retire(lightLut::close);
        if (matrices != null) Iris.getRenderBackend().retire(matrices::close);
        super.delete();
    }
}
