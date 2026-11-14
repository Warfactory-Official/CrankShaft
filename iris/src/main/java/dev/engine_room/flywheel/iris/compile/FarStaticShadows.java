package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.engine.RenderPassUniforms;
import dev.engine_room.flywheel.backend.engine.far.FarBuffer;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.iris.engine.GuestShadowCull;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.util.ArrayList;
import java.util.List;

/**
 * Frozen block-entity casters compacted against Iris's light frustum, independently of the main-view host.
 */
public final class FarStaticShadows implements AutoCloseable {
    static final List<BindGroupLayout> LAYOUTS = List.of(BindGroupLayout.builder()
                                                                        .withImageSampler("Sampler0")
                                                                        .withImageSampler("Sampler1")
                                                                        .withImageSampler("Sampler2")
                                                                        .withUniform("DynamicTransforms",
                                                                                UniformType.UNIFORM_BUFFER)
                                                                        .withUniform("Projection",
                                                                                UniformType.UNIFORM_BUFFER)
                                                                        .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                                                                        .withUniform("Globals",
                                                                                UniformType.UNIFORM_BUFFER)
                                                                        .withUniform("Lighting",
                                                                                UniformType.UNIFORM_BUFFER)
                                                                        .withUniform("_FlwRenderOrigin",
                                                                                UniformType.UNIFORM_BUFFER)
                                                                        .withUniform("_FlwInstanceDraw",
                                                                                UniformType.UNIFORM_BUFFER).build());

    private static @Nullable FarStaticShadows current;
    private final FarStaticDraw draw = new FarStaticDraw(false);
    private final FarBuffer inputs = new FarBuffer();
    private final FarBuffer groups = new FarBuffer();
    private final FarBuffer mapping = new FarBuffer();
    private final FarBuffer commands = new FarBuffer();
    private final Int2ObjectOpenHashMap<IntOpenHashSet> members = new Int2ObjectOpenHashMap<>();
    private final List<FarStaticMeshes.Template> templates = new ArrayList<>();
    private final FarStaticMeshes.Listener listener = this::changed;
    private final GuestShadowCull frustum = new GuestShadowCull();
    private final RenderPassUniforms uniforms = new RenderPassUniforms();
    private final AsyncGlProgram reset = compute("far/static_shadow_reset", RESET);
    private final AsyncGlProgram cull = cullProgram();
    private @Nullable FarStaticMeshes meshes;
    private int inputCount;
    private boolean dirty;

    static void warmUp() {
        if (current == null) current = new FarStaticShadows();
    }

    /**
     * Called within Iris's opaque shadow callback, after ordinary guest preparation has completed.
     */
    public static void render(IrisRenderingPipeline pipeline, Matrix4fc modelView, Vec3i origin, Vec3 camera,
                              boolean constantAmbientLight) {
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        var far = manager == null ? null : manager.farVisuals();
        var meshes = far == null ? null : far.staticMeshes();
        if (meshes == null || meshes.size() == 0) return;
        if (current == null) current = new FarStaticShadows();
        current.attach(meshes);
        current.renderMeshes(pipeline, modelView, origin, camera, constantAmbientLight);
    }

    /**
     * Pipeline reload/device teardown releases the source listener and all caster-owned GPU allocations.
     */
    public static void reset() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    /**
     * A level engine releases its capture membership; pipeline-owned programs remain available for later levels.
     */
    public static void releaseSource() {
        if (current == null) return;
        if (current.meshes != null) current.meshes.removeListener(current.listener);
        current.meshes = null;
        current.members.clear();
        current.templates.clear();
        current.inputCount = 0;
        current.inputs.delete();
        current.groups.delete();
        current.mapping.delete();
        current.commands.delete();
        current.dirty = false;
    }

    private void attach(FarStaticMeshes source) {
        if (meshes == source) return;
        if (meshes != null) meshes.removeListener(listener);
        members.clear();
        meshes = source;
        source.addListener(listener);
        for (var entry : source.entries()) changed(null, entry);
        dirty = true;
    }

    private void changed(FarStaticMeshes.@Nullable Entry previous, FarStaticMeshes.@Nullable Entry next) {
        if (previous == null && next == null) members.clear();
        if (previous != null) {
            for (var instance : previous.instances()) {
                var slots = members.get(instance.templateId());
                slots.remove(instance.slot());
                if (slots.isEmpty()) members.remove(instance.templateId());
            }
        }
        if (next != null) {
            for (var instance : next.instances()) {
                members.computeIfAbsent(instance.templateId(), _ -> new IntOpenHashSet()).add(instance.slot());
            }
        }
        dirty = true;
    }

    private static boolean casts(Material material) {
        var transparency = material.transparency();
        return material.writeMask().depth() && transparency != Transparency.ADDITIVE
                && transparency != Transparency.LIGHTNING && transparency != Transparency.ORDER_INDEPENDENT_ADDITIVE;
    }

    private void upload() {
        if (!dirty) return;
        templates.clear();
        var source = new IntArrayList();
        var descriptor = new IntArrayList();
        inputCount = 0;
        for (var entry : members.int2ObjectEntrySet()) {
            var template = meshes.template(entry.getIntKey());
            if (!casts(template.material())) continue;
            int group = templates.size();
            templates.add(template);
            descriptor.add(template.id());
            descriptor.add(inputCount);
            descriptor.add(entry.getValue().size());
            descriptor.add(0);
            for (int slot : entry.getValue()) {
                source.add(slot);
                source.add(group);
                inputCount++;
            }
        }
        if (inputCount != 0) {
            inputs.ensure((long) inputCount * 8);
            groups.ensure((long) templates.size() * 16);
            mapping.ensure((long) inputCount * 8);
            commands.ensure((long) templates.size() * FarStaticDraw.COMMAND_BYTES);
            inputs.write(0, source.elements(), source.size());
            groups.write(0, descriptor.elements(), descriptor.size());
        }
        dirty = false;
    }

    private void renderMeshes(IrisRenderingPipeline pipeline, Matrix4fc modelView, Vec3i origin, Vec3 camera,
                              boolean constantAmbientLight) {
        try (var state = FarGlState.capture()) {
            var reset = this.reset.get();
            var cull = this.cull.get();
            boolean ready = reset != null && cull != null;
            for (var entry : members.int2ObjectEntrySet()) {
                var material = meshes.template(entry.getIntKey()).material();
                if (casts(material) && GuestPipelines.staticShadowProgram(pipeline, material) == null) ready = false;
            }
            if (!ready) return;
            upload();
            if (inputCount == 0) return;
            var directives = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shadowDirectives();
            double distance = directives.getDistanceRenderMul() < 0
                    ? ShadowRenderingState.getRenderDistance() * 16.0
                    : directives.getDistance() * directives.getDistanceRenderMul();
            if (!frustum.bindFrustum(origin, camera, distance)) return;
            inputs.bindStorage(0);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, meshes.instanceBuffer());
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, meshes.templateBuffer());
            groups.bindStorage(3);
            mapping.bindStorage(4);
            commands.bindStorage(5);
            GlCompat.pushDebugGroup("far/static_shadow_cull");
            try {
                reset.bind();
                reset.setUInt("groupCount", templates.size());
                GL43C.glDispatchCompute((templates.size() + 63) / 64, 1, 1);
                GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
                cull.bind();
                cull.setUInt("inputCount", inputCount);
                GL20C.glUniform3i(cull.getUniformLocation("renderOrigin"), origin.getX(), origin.getY(), origin.getZ());
                GL43C.glDispatchCompute((inputCount + 127) / 128, 1, 1);
                GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_COMMAND_BARRIER_BIT);
            } finally {
                GlCompat.popDebugGroup();
            }
            GlCompat.pushDebugGroup("far/static_shadow_draw");
            try {
                drawMeshes(pipeline, modelView, origin, constantAmbientLight);
            } finally {
                GlCompat.popDebugGroup();
            }
        }
    }

    private void drawMeshes(IrisRenderingPipeline pipeline, Matrix4fc modelView, Vec3i origin,
                            boolean constantAmbientLight) {
        GuestSsbos.bindForGuest(pipeline);
        uniforms.beginFrame(origin, constantAmbientLight);
        bindUniform(0, RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView)));
        bindUniform(1, RenderSystem.getProjectionMatrixBuffer());
        bindUniform(2, RenderSystem.getShaderFog());
        bindUniform(3, RenderSystem.getGlobalSettingsUniform().slice());
        bindUniform(4, RenderSystem.getShaderLights());
        bindUniform(5, uniforms.renderOriginSlice());
        draw.geometry(meshes, mapping.handle(), 0, 0, 1, 2);
        int depthFunc = NativeDepth.active() ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL;
        for (int index = 0; index < templates.size(); index++) {
            var template = templates.get(index);
            var program = GuestPipelines.staticShadowProgram(pipeline, template.material());
            bindUniform(6, uniforms.material(MaterialEncoder.packProperties(template.material())));
            var albedo = (GlTextureView) Minecraft.getInstance().getTextureManager()
                                                  .getTexture(template.material().texture()).getTextureView();
            bindTexture(0, albedo, (GlSampler) MaterialSamplers.get(template.material()));
            var sampler = (GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            bindTexture(1, (GlTextureView) Minecraft.getInstance().gameRenderer.overlayTexture().getTextureView(),
                    sampler);
            bindTexture(2, (GlTextureView) Minecraft.getInstance().gameRenderer.lightmap(), sampler);
            pipeline.onSetAlbedoTex(albedo);
            program.setupStaticShadow(modelView, origin);
            try {
                GlStateManager._enableDepthTest();
                // Raw draws bypass Iris's native-pipeline inversion; shadow depth stays forward-Z unless the pack
                // takes raw reversed depth.
                GlStateManager._depthFunc(depthFunc);
                GlStateManager._depthMask(true);
                GlStateManager._disableCull();
                GlStateManager._disableScissorTest();
                for (int target = 0; target < 8; target++) GlStateManager._colorMask(target, 15);
                FarStaticDraw.indirect(new FarStaticDraw.DrawBatch(template.id(), commands.handle(),
                        (long) index * FarStaticDraw.COMMAND_BYTES, 0, 0, 1));
            } finally {
                program.iris$clearState();
            }
        }
    }

    private static void bindUniform(int binding, GpuBufferSlice data) {
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, binding, ((GlBuffer) data.buffer()).handle(),
                data.offset(), data.length());
    }

    private static void bindTexture(int unit, GlTextureView texture, GlSampler sampler) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture.glId());
        GL33C.glBindSampler(unit, sampler.getId());
    }

    static void vertex(Compilation source) {
        FarStaticDraw.vertex(source, false, 0, 1, 2);
        source.appendComponent(new RawSource("frozen guest vertex", """
                void _flw_guestVertex() {
                    _flw_staticInit();
                    flw_vertexPos = vec4(_flw_staticWorld, 1.0);
                    flw_vertexNormal = normalize(_flw_staticNormal);
                    flw_vertexColor = _flw_staticColor;
                    flw_vertexTexCoord = _flw_staticUv;
                    flw_vertexOverlay = _flw_staticOverlay;
                    flw_vertexLight = _flw_staticLight;
                    _flw_staticIdentity = _flw_staticMaterial & 65535u;
                    _flw_drawTag = (1u << 24u) | (_flw_staticIdentity + 1u);
                    _flw_itemTag = 0u;
                    _flw_irisMidTexCoord = _flw_staticUv;
                    _flw_irisMidBlock = vec4(0.0);
                    #ifdef _FLW_GUEST_CONTRACT
                    _flw_packedMaterial = uvec2(_flw_drawTag, _flw_drawPackedMaterial.y);
                    clrwl_vertexTangent = _flw_guestTangent();
                    #endif
                }
                """));
    }

    private static AsyncGlProgram compute(String name, String body) {
        var source = new Compilation();
        source.version(GlslVersion.V430);
        source.appendComponent(new RawSource(name, COMMON));
        source.appendComponent(new RawSource(name, body));
        return AsyncGlProgram.compute(name, source, _ -> {
        });
    }

    private static AsyncGlProgram cullProgram() {
        return compute("far/static_shadow_cull", FlwPrograms.SOURCES
                .get(ResourceUtil.rl("iris/shadow_frustum.glsl")).source() + CULL);
    }

    @Override
    public void close() {
        if (meshes != null) meshes.removeListener(listener);
        draw.close();
        inputs.delete();
        groups.delete();
        mapping.delete();
        commands.delete();
        reset.close();
        cull.close();
        frustum.delete();
        uniforms.delete();
    }

    private static final String COMMON = """
            struct Instance { ivec4 anchor; uvec4 appearance; };
            struct Template { uvec4 draw; vec3 lo; uint flags; vec3 hi; uint textureSlot; };
            struct Command { uint count; uint instanceCount; uint firstIndex; uint baseVertex; uint baseInstance; };
            layout(std430,binding=0) readonly buffer Inputs { uvec2 inputs[]; };
            layout(std430,binding=1) readonly buffer Instances { Instance instances[]; };
            layout(std430,binding=2) readonly buffer Templates { Template templates[]; };
            layout(std430,binding=3) readonly buffer Groups { uvec4 groups[]; };
            layout(std430,binding=4) writeonly buffer Mapping { uvec2 mapping[]; };
            layout(std430,binding=5) buffer Commands { Command commands[]; };
            """;
    private static final String RESET = """
            layout(local_size_x=64) in;
            uniform uint groupCount;
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index >= groupCount) return;
                uvec4 group = groups[index];
                uvec4 geometry = templates[group.x].draw;
                commands[index] = Command(geometry.y, 0u, geometry.x, geometry.z, group.y);
            }
            """;
    private static final String CULL = """
            layout(local_size_x=128) in;
            uniform uint inputCount;
            uniform ivec3 renderOrigin;
            shared uint localCounts[128];
            shared uint globalOffsets[128];
            void main() {
                uint index = gl_GlobalInvocationID.x;
                uint lane = gl_LocalInvocationID.x;
                uint workgroupBase = gl_WorkGroupID.x * gl_WorkGroupSize.x;
                localCounts[lane] = 0u;
                barrier();
            
                bool valid = index < inputCount;
                bool visible = false;
                uvec2 reference = uvec2(0u);
                uint firstLane = 0u;
                uint groupOffset = 0u;
                uint localRank = 0u;
                if (valid) {
                    reference = inputs[index];
                    groupOffset = groups[reference.y].y;
                    // References are contiguous per template, so its segment starts here or at this group's first lane.
                    firstLane = max(groupOffset, workgroupBase) - workgroupBase;
                    Instance instance = instances[reference.x];
                    Template geometry = templates[instance.anchor.w];
                    vec3 extent = (geometry.hi - geometry.lo) * 0.5;
                    vec3 center = vec3(instance.anchor.xyz - renderOrigin) + (geometry.hi + geometry.lo) * 0.5;
                    visible = _flw_shadowVisible(center + _flw_shadowOriginOffset, length(extent));
                    if (visible) localRank = atomicAdd(localCounts[firstLane], 1u);
                }
                barrier();
            
                if (valid && lane == firstLane && localCounts[firstLane] != 0u) {
                    globalOffsets[firstLane] = atomicAdd(commands[reference.y].instanceCount, localCounts[firstLane]);
                }
                barrier();
            
                if (visible) {
                    uint target = groupOffset + globalOffsets[firstLane] + localRank;
                    mapping[target] = uvec2(reference.x, 0x80000000u);
                }
            }
            """;
}
