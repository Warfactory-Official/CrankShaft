package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.engine.uniform.LevelUniforms;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * Direct drawing of frozen geometry selected by a terrain host. The caller owns the framebuffer and borrowed GL
 * scope; this object owns only its vertex array, optional target framebuffer and native material program.
 */
public final class FarStaticDraw implements AutoCloseable {
    public static final int INSTANCE_BINDING = 13;
    public static final int MAPPING_BINDING = 14;
    public static final int CLIP_BINDING = 15;
    public static final int COMMAND_BYTES = 20;

    private final boolean uniformClip;
    private final int vertexArray = GL45C.glCreateVertexArrays();
    private @Nullable GlProgram nativeProgram;
    private @Nullable AsyncGlProgram nativeBuild;
    private int framebuffer;

    public FarStaticDraw(boolean uniformClip) {
        this.uniformClip = uniformClip;
        attribute(0, 3, GL11C.GL_FLOAT, false, 0, false);
        attribute(1, 4, GL11C.GL_UNSIGNED_BYTE, true, 12, false);
        attribute(2, 2, GL11C.GL_FLOAT, false, 16, false);
        attribute(3, 2, GL11C.GL_SHORT, false, 24, true);
        attribute(4, 2, GL11C.GL_UNSIGNED_SHORT, false, 28, true);
        attribute(5, 3, GL11C.GL_FLOAT, false, 32, false);
    }

    private void attribute(int index, int count, int type, boolean normalized, int offset, boolean integer) {
        GL45C.glEnableVertexArrayAttrib(vertexArray, index);
        GL45C.glVertexArrayAttribBinding(vertexArray, index, 0);
        if (integer) GL45C.glVertexArrayAttribIFormat(vertexArray, index, count, type, offset);
        else GL45C.glVertexArrayAttribFormat(vertexArray, index, count, type, normalized, offset);
    }

    /**
     * Binds the current GPU arenas and a host-selected instance mapping. Render thread, inside the host scope.
     */
    public void geometry(FarStaticMeshes meshes, int mappingBuffer, int clipBuffer) {
        geometry(meshes, mappingBuffer, clipBuffer, INSTANCE_BINDING, MAPPING_BINDING, CLIP_BINDING);
    }

    public void geometry(FarStaticMeshes meshes, int mappingBuffer, int clipBuffer, int instanceBinding,
                         int mappingBinding, int clipBinding) {
        GL45C.glVertexArrayVertexBuffer(vertexArray, 0, meshes.vertexBuffer(), 0, FarStaticMeshes.VERTEX_STRIDE);
        GL45C.glVertexArrayElementBuffer(vertexArray, meshes.indexBuffer());
        GlStateManager._glBindVertexArray(vertexArray);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, instanceBinding, meshes.instanceBuffer());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, mappingBinding, mappingBuffer);
        if (!uniformClip) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, clipBinding, clipBuffer);
    }

    /**
     * For hosts exposing texture IDs rather than an already-bound framebuffer.
     */
    public void target(int color, int depth, int width, int height) {
        if (framebuffer == 0) framebuffer = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, color, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
        GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL30C.GL_COLOR_ATTACHMENT0);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._viewport(0, 0, width, height);
    }

    /**
     * Keeps a pack's current colour attachments while changing its depth attachment to the main view.
     */
    public void mainTarget(int targets, int depth, int width, int height) {
        int source = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) framebuffer = GL45C.glCreateFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            var attachments = stack.mallocInt(targets);
            for (int i = 0; i < targets; i++) {
                int attachment = GL30C.GL_COLOR_ATTACHMENT0 + i;
                int texture = GL45C.glGetNamedFramebufferAttachmentParameteri(source, attachment,
                        GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                GL45C.glNamedFramebufferTexture(framebuffer, attachment, texture, 0);
                attachments.put(i, attachment);
            }
            GL45C.glNamedFramebufferDrawBuffers(framebuffer, attachments);
        }
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_DEPTH_ATTACHMENT, depth, 0);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._viewport(0, 0, width, height);
    }

    public static void camera(GlProgram program, Vec3 camera) {
        int x = (int) Math.floor(camera.x);
        int y = (int) Math.floor(camera.y);
        int z = (int) Math.floor(camera.z);
        GL20C.glUniform3i(program.getUniformLocation("_flw_staticCamera"), x, y, z);
        program.setVec3("_flw_staticCameraFraction", (float) (camera.x - x), (float) (camera.y - y),
                (float) (camera.z - z));
    }

    public static void clip(GlProgram program, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        GL20C.glUniform3i(program.getUniformLocation("_flw_staticClipMin"), minX, minY, minZ);
        GL20C.glUniform3i(program.getUniformLocation("_flw_staticClipMax"), maxX, maxY, maxZ);
    }

    public static void baseInstance(GlProgram program, int firstInstance) {
        program.setUInt("_flw_staticBaseInstance", firstInstance);
    }

    /**
     * Binds source appearance once per template batch; every draw in that batch has the same sampler.
     */
    public void material(GlProgram program, FarStaticMeshes.Template template, int albedoUnit, int overlayUnit) {
        Material material = template.material();
        program.setUInt("_flw_staticFlags", FarStaticMeshes.flags(material));
        program.setUInt("_flw_staticTextureSlot", template.textureSlot());
        program.setSamplerBinding("_flw_staticAlbedo", albedoUnit);
        program.setSamplerBinding("_flw_staticOverlayTexture", overlayUnit);
        var texture = (GlTextureView) Minecraft.getInstance().getTextureManager().getTexture(material.texture())
                                               .getTextureView();
        GlStateManager._activeTexture(GL13_TEXTURE0 + albedoUnit);
        if (albedoUnit < 12) GlStateManager._bindTexture(texture.glId());
        else GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture.glId());
        GL33C.glBindSampler(albedoUnit, ((GlSampler) MaterialSamplers.get(material)).getId());
        if (material.backfaceCulling()) GlStateManager._enableCull();
        else GlStateManager._disableCull();
    }

    public boolean bindNative(Matrix4fc viewProjection, Vec3 camera, boolean zeroToOne, boolean reverseZ,
                              boolean nether) {
        if (nativeBuild == null) nativeBuild = nativeProgram(uniformClip);
        nativeProgram = nativeBuild.get();
        if (nativeProgram == null) return false;
        GlStateManager._glUseProgram(nativeProgram.handle());
        nativeProgram.setMat4("_flw_staticViewProjection", viewProjection);
        camera(nativeProgram, camera);
        boolean hardwareZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        float scale = zeroToOne == hardwareZeroToOne ? 1.0f : hardwareZeroToOne ? 0.5f : 2.0f;
        float offset = zeroToOne == hardwareZeroToOne ? 0.0f : hardwareZeroToOne ? 0.5f : -1.0f;
        nativeProgram.setVec2("_flw_staticDepthTransform", scale, offset);
        nativeProgram.setBool("_flw_staticNether", nether);
        nativeProgram.setFloat("_flw_staticMainRadius", 0);
        terrainDepth(null, 13);
        var light0 = LevelUniforms.LIGHT0_DIRECTION;
        var light1 = LevelUniforms.LIGHT1_DIRECTION;
        nativeProgram.setVec3("_flw_staticLight0", light0.x, light0.y, light0.z);
        nativeProgram.setVec3("_flw_staticLight1", light1.x, light1.y, light1.z);
        bindOverlay(nativeProgram, 11);
        nativeProgram.setSamplerBinding("_flw_staticLightmap", 12);
        bindTexture(12, (GlTextureView) Minecraft.getInstance().gameRenderer.lightmap(),
                (GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(reverseZ ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._disableScissorTest();
        GlStateManager._disableBlend(0);
        GlStateManager._colorMask(0, 15);
        return true;
    }

    public void nativeBatch(FarStaticMeshes.Template template, int firstInstance, int instanceCount,
                            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        material(nativeProgram, template, 10, 11);
        clip(nativeProgram, minX, minY, minZ, maxX, maxY, maxZ);
        baseInstance(nativeProgram, firstInstance);
        direct(template, firstInstance, instanceCount);
    }

    /**
     * Restricts the late main-view draw to the area where vanilla can cover a terrain host's depth.
     */
    public void mainRadius(float radius) {
        nativeProgram.setFloat("_flw_staticMainRadius", radius);
    }

    /**
     * Supplies opaque host depth independently of the main view's hardware depth attachment.
     */
    public void terrainDepth(@Nullable TerrainDepth source, int unit) {
        terrainDepth(nativeProgram, source, unit);
    }

    /**
     * A null source selects the hardware-depth-only path used by a host's own terrain draw.
     */
    public static void terrainDepth(GlProgram program, @Nullable TerrainDepth source, int unit) {
        program.setBool("_flw_staticTerrainDepthEnabled", source != null);
        program.setSamplerBinding("_flw_staticTerrainDepth", unit);
        if (source == null) return;
        program.setMat4("_flw_staticTerrainProjection", source.worldToClip());
        program.setBool("_flw_staticTerrainZeroToOne", source.zeroToOne());
        program.setBool("_flw_staticTerrainReverseZ", source.reverseZ());
        GlStateManager._activeTexture(GL13_TEXTURE0 + unit);
        if (unit < 12) GlStateManager._bindTexture(source.texture());
        else GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, source.texture());
        GL33C.glBindSampler(unit, ((GlSampler) RenderSystem.getSamplerCache()
                                                           .getClampToEdge(FilterMode.NEAREST)).getId());
    }

    /**
     * Camera-relative world coordinates to the raw opaque host texture, captured in the current frame.
     */
    public record TerrainDepth(int texture, Matrix4fc worldToClip, boolean zeroToOne, boolean reverseZ) {
    }

    public void nativeBatch(FarStaticMeshes meshes, DrawBatch batch) {
        material(nativeProgram, meshes.template(batch.templateId()), 10, 11);
        indirect(batch);
    }

    public static void direct(FarStaticMeshes.Template template, int firstInstance, int instanceCount) {
        GL42C.glDrawElementsInstancedBaseVertexBaseInstance(GL11C.GL_TRIANGLES, template.indexCount(),
                GL11C.GL_UNSIGNED_INT, (long) template.firstIndex() * Integer.BYTES, instanceCount,
                template.firstVertex(), firstInstance);
    }

    public static void indirect(DrawBatch batch) {
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, batch.commandBuffer());
        if (batch.countBuffer() == 0) {
            GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT,
                    batch.commandOffset(), batch.maxDrawCount(), COMMAND_BYTES);
        } else {
            GL15C.glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, batch.countBuffer());
            ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT,
                    batch.commandOffset(), batch.countOffset(), batch.maxDrawCount(), COMMAND_BYTES);
        }
    }

    public static void bindOverlay(GlProgram program, int unit) {
        program.setSamplerBinding("_flw_staticOverlayTexture", unit);
        bindTexture(unit, (GlTextureView) Minecraft.getInstance().gameRenderer.overlayTexture().getTextureView(),
                (GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private static final int GL13_TEXTURE0 = 0x84C0;

    private static void bindTexture(int unit, GlTextureView texture, GlSampler sampler) {
        GlStateManager._activeTexture(GL13_TEXTURE0 + unit);
        if (unit < 12) GlStateManager._bindTexture(texture.glId());
        else GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture.glId());
        GL33C.glBindSampler(unit, sampler.getId());
    }

    private static AsyncGlProgram nativeProgram(boolean uniformClip) {
        var vertex = new Compilation();
        vertex.version(GlslVersion.V430);
        vertex(vertex, uniformClip, INSTANCE_BINDING, MAPPING_BINDING, CLIP_BINDING);
        vertex.appendComponent(new RawSource("static terrain native projection", """
                uniform mat4 _flw_staticViewProjection;
                uniform vec2 _flw_staticDepthTransform;
                void main() {
                    _flw_staticInit();
                    gl_Position = _flw_staticViewProjection * vec4(_flw_staticWorld, 1.0);
                    gl_Position.z = gl_Position.z * _flw_staticDepthTransform.x
                            + gl_Position.w * _flw_staticDepthTransform.y;
                }
                """));
        var fragment = new Compilation();
        fragment.version(GlslVersion.V430);
        fragment.appendComponent(new RawSource("static terrain appearance", FRAGMENT));
        fragment.appendComponent(new RawSource("static opaque host depth", TERRAIN_DEPTH_FRAGMENT));
        fragment.appendComponent(new RawSource("static terrain native lighting", """
                uniform sampler2D _flw_staticLightmap;
                uniform vec3 _flw_staticLight0;
                uniform vec3 _flw_staticLight1;
                uniform bool _flw_staticNether;
                layout(location=0) out vec4 fragColor;
                void main() {
                    _flw_staticClip();
                    if (_flw_staticTerrainOccluded()) discard;
                    vec4 color = _flw_staticSampleColor(_flw_staticColor);
                    vec3 normal = normalize(_flw_staticNormal);
                    float diffuse = 1.0;
                    if ((_flw_staticFlags & 16u) != 0u) {
                        diffuse = min(1.0, (max(0.0, dot(_flw_staticLight0, normal))
                                + max(0.0, dot(_flw_staticLight1, normal))) * 0.6 + 0.4);
                    } else if ((_flw_staticFlags & 32u) != 0u) {
                        vec3 square = normal * normal;
                        diffuse = _flw_staticNether ? min(dot(square, vec3(0.6, 0.9, 0.8)), 1.0)
                                : min(square.x * 0.6 + square.y * 0.25 * (3.0 + normal.y)
                                        + square.z * 0.8, 1.0);
                    }
                    color.rgb *= diffuse;
                    if ((_flw_staticFlags & 2u) != 0u) {
                        vec2 light = clamp(_flw_staticLight + 0.5 / 16.0,
                                vec2(0.5 / 16.0), vec2(15.5 / 16.0));
                        color *= texture(_flw_staticLightmap, light);
                    }
                    fragColor = color;
                }
                """));
        return AsyncGlProgram.graphics("far/static_native", vertex, fragment, _ -> {
        });
    }

    /**
     * Explicit diagnostic readback of the commands most recently generated by a terrain host.
     */
    public static Submitted submitted(List<DrawBatch> batches) {
        int drawCount = 0;
        long instances = 0;
        long indices = 0;
        try (var stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1);
            for (var batch : batches) {
                int draws = batch.maxDrawCount();
                if (batch.countBuffer() != 0) {
                    GL45C.glGetNamedBufferSubData(batch.countBuffer(), batch.countOffset(), count);
                    draws = Math.min(draws, count.get(0));
                }
                if (draws == 0) continue;
                var commands = MemoryUtil.memAllocInt(draws * COMMAND_BYTES / Integer.BYTES);
                try {
                    GL45C.glGetNamedBufferSubData(batch.commandBuffer(), batch.commandOffset(), commands);
                    for (int draw = 0; draw < draws; draw++) {
                        int at = draw * COMMAND_BYTES / Integer.BYTES;
                        int submitted = commands.get(at + 1);
                        if (submitted == 0) continue;
                        drawCount++;
                        instances += submitted;
                        indices += (long) commands.get(at) * submitted;
                    }
                } finally {
                    MemoryUtil.memFree(commands);
                }
            }
        }
        return new Submitted(drawCount, instances, indices);
    }

    public record DrawBatch(int templateId, int commandBuffer, long commandOffset, int countBuffer, long countOffset,
                            int maxDrawCount) {
    }

    public record Submitted(int draws, long instances, long indices) {
    }

    @Override
    public void close() {
        if (nativeBuild != null) nativeBuild.close();
        GL30C.glDeleteVertexArrays(vertexArray);
        if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
    }

    /**
     * Assembles a concrete decoder variant so AST consumers do not need to preserve preprocessor branches.
     */
    public static void vertex(Compilation source, boolean uniformClip, int instanceBinding, int mappingBinding,
                              int clipBinding) {
        source.appendComponent(new RawSource("static geometry attributes", VERTEX_INPUTS));
        source.appendComponent(new RawSource("static instance arena", "layout(std430,binding=" + instanceBinding
                + ") readonly buffer _FlwStaticInstances { _FlwStaticInstance _flw_staticInstances[]; };"));
        if (uniformClip) {
            source.appendComponent(new RawSource("static uniform section mapping", "layout(std430,binding="
                    + mappingBinding + ") readonly buffer _FlwStaticMapping { uint _flw_staticMapping[]; };\n"
                    + "uniform ivec3 _flw_staticClipMin;\nuniform ivec3 _flw_staticClipMax;\n"
                    + "uniform uint _flw_staticBaseInstance;"));
        } else {
            source.requireExtension("GL_ARB_shader_draw_parameters");
            source.appendComponent(new RawSource("static indexed section mapping", "layout(std430,binding="
                    + mappingBinding + ") readonly buffer _FlwStaticMapping { uvec2 _flw_staticMapping[]; };\n"
                    + "struct _FlwStaticClip { ivec4 lo; ivec4 hi; };\nlayout(std430,binding=" + clipBinding
                    + ") readonly buffer _FlwStaticClips { _FlwStaticClip _flw_staticClips[]; };"));
        }
        source.appendComponent(new RawSource("static geometry varyings", VERTEX_OUTPUTS));
        if (uniformClip) {
            source.appendComponent(new RawSource("static uniform instance address", """
                    void _flw_staticInit() {
                        uint at = _flw_staticBaseInstance + uint(gl_InstanceID);
                        uint slot = _flw_staticMapping[at];
                        bool clipSection = true;
                        ivec3 clipMin = _flw_staticClipMin;
                        ivec3 clipMax = _flw_staticClipMax;
                    """));
        } else {
            source.appendComponent(new RawSource("static indirect instance address", """
                    void _flw_staticInit() {
                        uint at = uint(gl_BaseInstanceARB) + uint(gl_InstanceID);
                        uvec2 mapped = _flw_staticMapping[at];
                        uint slot = mapped.x;
                        bool clipSection = (mapped.y & 0x80000000u) == 0u;
                        ivec3 clipMin = ivec3(0);
                        ivec3 clipMax = ivec3(0);
                        if (clipSection) {
                            uint clipIndex = mapped.y & 0x7fffffffu;
                            clipMin = _flw_staticClips[clipIndex].lo.xyz;
                            clipMax = _flw_staticClips[clipIndex].hi.xyz;
                        }
                    """));
        }
        source.appendComponent(new RawSource("static captured vertex decode", VERTEX_DECODE));
    }

    private static final String VERTEX_INPUTS = """
            layout(location=0) in vec3 _flw_staticPosition;
            layout(location=1) in vec4 _flw_staticVertexColor;
            layout(location=2) in vec2 _flw_staticVertexUv;
            layout(location=3) in ivec2 _flw_staticVertexOverlay;
            layout(location=4) in uvec2 _flw_staticVertexLight;
            layout(location=5) in vec3 _flw_staticVertexNormal;
            struct _FlwStaticInstance { ivec4 anchor; uvec4 appearance; };
            """;

    private static final String VERTEX_OUTPUTS = """
            uniform ivec3 _flw_staticCamera;
            uniform vec3 _flw_staticCameraFraction;
            uniform uint _flw_staticTextureSlot;
            out vec2 _flw_staticUv;
            out vec4 _flw_staticColor;
            out vec3 _flw_staticNormal;
            out vec2 _flw_staticLight;
            flat out ivec2 _flw_staticOverlay;
            flat out uint _flw_staticMaterial;
            flat out uint _flw_farTextureSlot;
            out vec3 _flw_staticWorld;
            out vec3 _flw_staticClipPosition;
            flat out vec3 _flw_staticClipExtent;
            flat out uint _flw_staticSectionClip;
            """;

    private static final String VERTEX_DECODE = """
                _FlwStaticInstance instance = _flw_staticInstances[slot];
                _flw_staticWorld = vec3(instance.anchor.xyz - _flw_staticCamera)
                        - _flw_staticCameraFraction + _flw_staticPosition;
                _flw_staticSectionClip = uint(clipSection);
                _flw_staticClipPosition = vec3(0.0);
                _flw_staticClipExtent = vec3(0.0);
                if (clipSection) {
                    _flw_staticClipPosition = vec3(instance.anchor.xyz - clipMin) + _flw_staticPosition;
                    _flw_staticClipExtent = vec3(clipMax - clipMin);
                }
                _flw_staticUv = _flw_staticVertexUv;
                _flw_staticColor = _flw_staticVertexColor * unpackUnorm4x8(instance.appearance.x);
                _flw_staticNormal = _flw_staticVertexNormal;
                uvec2 light = uvec2(instance.appearance.y & 65535u, instance.appearance.y >> 16u);
                _flw_staticLight = vec2(max(light, _flw_staticVertexLight)) / 256.0;
                _flw_staticOverlay = ivec2(instance.appearance.z & 65535u, instance.appearance.z >> 16u);
                _flw_staticMaterial = instance.appearance.w;
                _flw_farTextureSlot = _flw_staticTextureSlot;
            }
            """;

    public static final String FRAGMENT = """
            uniform sampler2D _flw_staticAlbedo;
            uniform sampler2D _flw_staticOverlayTexture;
            uniform uint _flw_staticFlags;
            uniform float _flw_staticMainRadius;
            in vec2 _flw_staticUv;
            in vec4 _flw_staticColor;
            in vec3 _flw_staticNormal;
            in vec2 _flw_staticLight;
            flat in ivec2 _flw_staticOverlay;
            flat in uint _flw_staticMaterial;
            in vec3 _flw_staticWorld;
            in vec3 _flw_staticClipPosition;
            flat in vec3 _flw_staticClipExtent;
            flat in uint _flw_staticSectionClip;
            void _flw_staticClip() {
                if (_flw_staticSectionClip != 0u && (any(lessThan(_flw_staticClipPosition, vec3(0.0)))
                        || any(greaterThanEqual(_flw_staticClipPosition, _flw_staticClipExtent)))) discard;
                if (_flw_staticMainRadius > 0.0
                        && length(_flw_staticWorld.xz) > _flw_staticMainRadius) discard;
            }
            vec4 _flw_staticSampleColor(vec4 color) {
                color *= texture(_flw_staticAlbedo, _flw_staticUv);
                if ((_flw_staticFlags & 8u) != 0u && color.a < 0.1) discard;
                if ((_flw_staticFlags & 1u) != 0u) {
                    vec4 overlay = texelFetch(_flw_staticOverlayTexture, _flw_staticOverlay, 0);
                    color.rgb = mix(overlay.rgb, color.rgb, overlay.a);
                }
                return color;
            }
            vec4 _flw_staticTangent() {
                vec3 normal = normalize(_flw_staticNormal);
                vec3 dx = dFdx(_flw_staticWorld);
                vec3 dy = dFdy(_flw_staticWorld);
                vec2 uvDx = dFdx(_flw_staticUv);
                vec2 uvDy = dFdy(_flw_staticUv);
                float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
                if (determinant == 0.0) {
                    vec3 axis = abs(normal.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                    return vec4(normalize(cross(axis, normal)), 1.0);
                }
                vec3 tangent = (dx * uvDy.y - dy * uvDx.y) / determinant;
                vec3 bitangent = (dy * uvDx.x - dx * uvDy.x) / determinant;
                tangent = normalize(tangent - normal * dot(normal, tangent));
                return vec4(tangent, dot(cross(normal, tangent), bitangent) < 0.0 ? -1.0 : 1.0);
            }
            """;

    public static final String TERRAIN_DEPTH_FRAGMENT = """
            uniform bool _flw_staticTerrainDepthEnabled;
            uniform sampler2D _flw_staticTerrainDepth;
            uniform mat4 _flw_staticTerrainProjection;
            uniform bool _flw_staticTerrainZeroToOne;
            uniform bool _flw_staticTerrainReverseZ;
            bool _flw_staticTerrainOccluded() {
                if (!_flw_staticTerrainDepthEnabled) return false;
                vec4 clip = _flw_staticTerrainProjection * vec4(_flw_staticWorld, 1.0);
                if (clip.w <= 0.0) return false;
                vec3 ndc = clip.xyz / clip.w;
                vec2 uv = ndc.xy * 0.5 + 0.5;
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return false;
                ivec2 texel = ivec2(uv * vec2(textureSize(_flw_staticTerrainDepth, 0)));
                float hostDepth = texelFetch(_flw_staticTerrainDepth, texel, 0).r;
                if (hostDepth <= 0.0 || hostDepth >= 1.0) return false;
                float depth = _flw_staticTerrainZeroToOne ? ndc.z : ndc.z * 0.5 + 0.5;
                return _flw_staticTerrainReverseZ ? depth < hostDepth : depth > hostDepth;
            }
            """;
}
