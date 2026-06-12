package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.component.SsboInstanceComponent;
import dev.engine_room.flywheel.backend.compile.component.UberMaterialShaderComponent;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class MeshVisualShaders {
    public static final long FRAME_UBO_BYTES = 80L;
    // GL mesh-visual f16 varying trim: color/light/normal ride the mesh->frag interface as float16 (better
    // occupancy, the confirmed limiter); GL_NV_gpu_shader5 is always present on the GL tier (NVIDIA-only).
    public static final Consumer<Compilation> GL_MESH_F16 = ctx -> {
        ctx.requireExtension("GL_NV_gpu_shader5");
        ctx.define("_FLW_MV_F16_VARYINGS");
    };
    public static final Consumer<Compilation> VK_MESH_F16 = ctx -> {
        if (VkCaps.MESH_F16_VARYINGS_NEGOTIATED) {
            ctx.requireExtension("GL_EXT_shader_explicit_arithmetic_types_float16");
            ctx.requireExtension("GL_EXT_shader_16bit_storage");
            ctx.define("_FLW_MV_F16_VARYINGS");
        }
    };
    private static final Identifier BUFFER_BINDINGS = ResourceUtil.rl("internal/indirect/buffer_bindings.glsl");
    private static final Identifier DRAW_COMMAND = ResourceUtil.rl("internal/indirect/draw_command.glsl");
    private static final Identifier MATERIAL = ResourceUtil.rl("internal/material.glsl");
    private static final Identifier PACKED_MATERIAL = ResourceUtil.rl("internal/packed_material.glsl");
    private static final Identifier DIFFUSE = ResourceUtil.rl("internal/diffuse.glsl");
    private static final Identifier INDIRECT_LIGHT = ResourceUtil.rl("internal/indirect/light.glsl");
    private static final Identifier MATRICES = ResourceUtil.rl("internal/indirect/matrices.glsl");
    private static final Identifier MATRIX_UTIL = ResourceUtil.rl("util/matrix.glsl");
    private static final Identifier MV_HEADER = ResourceUtil.rl("meshvisual/visual_header.glsl");
    private static final Identifier MV_MESH_MAIN = ResourceUtil.rl("meshvisual/visual_main.mesh");
    private static final Identifier MV_CRUMBLING_MESH_MAIN = ResourceUtil.rl("meshvisual/visual_crumbling_main.mesh");
    private static final Identifier MV_FRAGMENT = ResourceUtil.rl("meshvisual/visual.frag");
    private static final Identifier MV_OIT_FRAGMENT = ResourceUtil.rl("meshvisual/visual_oit.frag");
    private static final Identifier WAVELET = ResourceUtil.rl("internal/wavelet.glsl");
    private static final Identifier MV_COMMAND_BUILDER = ResourceUtil.rl("meshvisual/command_builder.comp");
    private static final Identifier MV_TASK_MAIN = ResourceUtil.rl("meshvisual/visual_task.task");
    private static final Identifier MV_MESHLET_CULL = ResourceUtil.rl("meshvisual/meshlet_cull.glsl");
    private static final Identifier MV_VK_HEADER = ResourceUtil.rl("meshvisual/vk/visual_header_vk.glsl");
    private static final Identifier MV_VK_PAYLOAD = ResourceUtil.rl("meshvisual/vk/visual_payload_vk.glsl");
    private static final Identifier MV_VK_MESH_MAIN = ResourceUtil.rl("meshvisual/vk/visual_main_vk.mesh");
    private static final Identifier MV_VK_TASK_MAIN = ResourceUtil.rl("meshvisual/vk/visual_task_vk.task");
    private static final Identifier MV_VK_MESHLET_CULL = ResourceUtil.rl("meshvisual/vk/meshlet_cull_vk.glsl");
    private static final Identifier MV_VK_CRUMBLING_MESH_MAIN = ResourceUtil.rl(
            "meshvisual/vk/visual_crumbling_main_vk.mesh");
    private static final Identifier MV_VK_COMMAND_BUILDER = ResourceUtil.rl("meshvisual/vk/command_builder_vk.comp");
    private static final Consumer<Compilation> NV_MESH_PREAMBLE = ctx -> {
        ctx.requireExtension("GL_NV_mesh_shader");
        ctx.requireExtension("GL_ARB_shader_draw_parameters");
    };
    private static final String FRAG_PRELUDE = """
            struct FlwLightAo { vec2 light; float ao; };
            layout(std140, binding = 6) uniform Lighting {
                vec3 Light0_Direction;
                vec3 Light1_Direction;
            };
            layout(std140, binding = 4) uniform _FlwRenderOrigin {
                ivec4 _flw_renderOrigin;
                uint _flw_constantAmbientLight;
            };
            layout(std140, binding = 9) uniform _FlwMeshVisualFrame {
                mat4 _flw_mvModelView;
                float _flw_mvSystemSeconds;
                float _flw_mvGlintSpeed;
                float _flw_mvGlintStrength;
            };
            // The per-material fragment hook's globals (RenderPassShaders.FRAG_LIGHTING_PRELUDE parity): the
            // fragment sets all three before flw_materialFragment(), which may rewrite flw_fragColor
            // (nametag.frag turns R8 glyph coverage into alpha).
            vec4 flw_fragColor;
            vec4 flw_sampleColor;
            vec4 flw_vertexColor;
            // The CLIP_* cutout predicates' input (compact ABI: x = dot(plane.xyz, slidPos), y = plane.w):
            // zero-init so non-clip programs compile (0 > 0 never discards -- the predicates degrade to the
            // plain alpha test). Under _FLW_MV_CLIP (clipExtra, clip-writing types only) the main overwrites
            // it from the real mesh-stage varying before the cutout dispatch.
            vec2 _flw_clipData = vec2(0.0);
            """;

    private MeshVisualShaders() {
    }

    /**
     * The {@code _FLW_MV_CLIP} interface variant: mesh-stage per-vertex clip varyings for the CLIP_* cutout
     * predicates. MUST be applied to the mesh AND fragment of a program pair, never one side.
     */
    public static Consumer<Compilation> clipExtra(InstanceType<?> type) {
        return referencesClipVaryings(FlwPrograms.SOURCES.get(type.vertexShader()))
                ? ctx -> ctx.define("_FLW_MV_CLIP")
                : ShaderAssembly.NO_EXTRA;
    }

    private static boolean referencesClipVaryings(SourceComponent component) {
        String source = component.source();
        if (source.contains("_flw_clipData")) {
            return true;
        }
        for (SourceComponent included : component.included()) {
            if (referencesClipVaryings(included)) {
                return true;
            }
        }
        return false;
    }

    private static String assembleRaw(Consumer<Compilation> preamble, List<SourceComponent> roots) {
        return ShaderAssembly.assemble(preamble, roots);
    }

    private static String assembleFragment(Consumer<Compilation> preamble, List<SourceComponent> roots) {
        return ShaderAssembly.assembleFlattened(preamble, roots);
    }

    public static String assembleMesh(InstanceType<?> type, Identifier materialVertex, Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>();
        // NO frame.glsl: it would put _flw_renderOrigin in both _FlwFrameUniforms and _FlwRenderOrigin -- a cross-stage link error.
        roots.add(FlwPrograms.SOURCES.get(BUFFER_BINDINGS));
        roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        roots.add(FlwPrograms.SOURCES.get(MATRICES));
        roots.add(FlwPrograms.SOURCES.get(MV_HEADER));
        roots.add(new InstanceStructComponent(type));
        roots.add(new SsboInstanceComponent(type));
        roots.add(FlwPrograms.SOURCES.get(type.vertexShader()));
        roots.add(FlwPrograms.SOURCES.get(materialVertex));
        roots.add(FlwPrograms.SOURCES.get(MV_MESH_MAIN));
        return assembleRaw(NV_MESH_PREAMBLE.andThen(MeshVisualShaders::materialVertexDefines)
                                           .andThen(GL_MESH_F16).andThen(extra), roots);
    }

    public static String assembleCrumblingMesh(InstanceType<?> type, Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(BUFFER_BINDINGS));
        roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        roots.add(FlwPrograms.SOURCES.get(MATRICES));
        roots.add(FlwPrograms.SOURCES.get(MV_HEADER));
        roots.add(new InstanceStructComponent(type));
        roots.add(new SsboInstanceComponent(type));
        roots.add(FlwPrograms.SOURCES.get(type.vertexShader()));
        roots.add(FlwPrograms.SOURCES.get(MV_CRUMBLING_MESH_MAIN));
        return assembleRaw(NV_MESH_PREAMBLE.andThen(extra), roots);
    }

    // The only frame/options uniforms material vertex shaders can read on the mesh tiers: the full blocks clash with the fragment's across the linked program.
    private static void materialVertexDefines(Compilation ctx) {
        ctx.define("flw_systemSeconds", "_flw_mvSystemSeconds");
        ctx.define("flw_glintSpeedOption", "_flw_mvGlintSpeed");
        ctx.define("flw_glintStrengthOption", "_flw_mvGlintStrength");
        ctx.define("flw_view", "_flw_mvModelView");
    }

    // Optional NV task stage: per-meshlet frustum cull ahead of the mesh stage (large/unwelded models).
    public static String assembleTask(InstanceType<?> type) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(BUFFER_BINDINGS));
        roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        roots.add(FlwPrograms.SOURCES.get(MATRICES));
        roots.add(FlwPrograms.SOURCES.get(MATRIX_UTIL));
        roots.add(FlwPrograms.SOURCES.get(MV_HEADER));
        roots.add(new InstanceStructComponent(type));
        roots.add(new SsboInstanceComponent(type));
        roots.add(FlwPrograms.SOURCES.get(type.cullShader()));
        roots.add(FlwPrograms.SOURCES.get(MV_MESHLET_CULL));
        roots.add(FlwPrograms.SOURCES.get(MV_TASK_MAIN));
        return assembleRaw(NV_MESH_PREAMBLE.andThen(ctx -> ctx.requireExtension("GL_KHR_shader_subgroup_ballot")),
                roots);
    }

    private static void vkMeshPreamble(Compilation ctx) {
        ctx.requireExtension("GL_EXT_mesh_shader");
        ctx.requireExtension("GL_EXT_buffer_reference2");
        ctx.requireExtension("GL_EXT_shader_explicit_arithmetic_types_int64");
        vkMeshCapDefines(ctx);
    }

    // Vulkan twin of assembleMesh (EXT-dialect header/main -- already Vulkan GLSL, so NO VkShaderTransform pass needed).
    public static String assembleVkMesh(InstanceType<?> type, Identifier materialVertex, Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(BUFFER_BINDINGS));
        roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        roots.add(FlwPrograms.SOURCES.get(MATRICES));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_HEADER));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_PAYLOAD));
        roots.add(new InstanceStructComponent(type));
        roots.add(new SsboInstanceComponent(type));
        roots.add(FlwPrograms.SOURCES.get(type.vertexShader()));
        roots.add(FlwPrograms.SOURCES.get(materialVertex));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_MESH_MAIN));
        return assembleRaw(ctx -> {
            vkMeshPreamble(ctx);
            materialVertexDefines(ctx);
            VK_MESH_F16.accept(ctx);
            extra.accept(ctx);
        }, roots);
    }

    public static String assembleVkCrumblingMesh(InstanceType<?> type, Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(BUFFER_BINDINGS));
        roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        roots.add(FlwPrograms.SOURCES.get(MATRICES));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_HEADER));
        roots.add(new InstanceStructComponent(type));
        roots.add(new SsboInstanceComponent(type));
        roots.add(FlwPrograms.SOURCES.get(type.vertexShader()));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_CRUMBLING_MESH_MAIN));
        return assembleRaw(((Consumer<Compilation>) MeshVisualShaders::vkMeshPreamble).andThen(extra), roots);
    }

    // Vulkan twin of assembleTask: the K-batched EXT task stage (welded fan-out + unwelded per-meshlet frustum+HiZ cull).
    public static String assembleVkTask(InstanceType<?> type) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(BUFFER_BINDINGS));
        roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        roots.add(FlwPrograms.SOURCES.get(MATRICES));
        roots.add(FlwPrograms.SOURCES.get(MATRIX_UTIL));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_HEADER));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_PAYLOAD));
        roots.add(new InstanceStructComponent(type));
        roots.add(new SsboInstanceComponent(type));
        roots.add(FlwPrograms.SOURCES.get(type.cullShader()));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_MESHLET_CULL));
        roots.add(FlwPrograms.SOURCES.get(MV_VK_TASK_MAIN));
        return assembleRaw(((Consumer<Compilation>) MeshVisualShaders::vkMeshPreamble)
                .andThen(ctx -> ctx.requireExtension("GL_KHR_shader_subgroup_ballot")), roots);
    }

    public static String assembleVkCommandBuilder() {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(BUFFER_BINDINGS),
                FlwPrograms.SOURCES.get(DRAW_COMMAND),
                FlwPrograms.SOURCES.get(MV_VK_COMMAND_BUILDER));
        return assembleRaw(MeshVisualShaders::vkMeshCapDefines, roots);
    }

    private static void vkMeshCapDefines(Compilation ctx) {
        ctx.define("_FLW_MV_MAX_VERTS", String.valueOf(VkCaps.MESH_MAX_OUTPUT_VERTICES));
        ctx.define("_FLW_MV_MAX_PRIMS", String.valueOf(VkCaps.MESH_MAX_OUTPUT_PRIMITIVES));
        ctx.define("_FLW_MV_MAX_WG_X", String.valueOf(VkCaps.MESH_MAX_WORKGROUP_COUNT_X));
    }

    private static void fragLightDefines(Compilation ctx) {
        ctx.define("flw_light0Direction", "Light0_Direction");
        ctx.define("flw_light1Direction", "Light1_Direction");
        ctx.define("flw_renderOrigin", "(_flw_renderOrigin.xyz)");
        ctx.define("flw_constantAmbientLight", "_flw_constantAmbientLight");
        ctx.define("_FLW_LIGHT_LUT_BUFFER_BINDING", "5");
        ctx.define("_FLW_LIGHT_SECTIONS_BUFFER_BINDING", "6");
        BackendConfig.INSTANCE.lightSmoothness()
                              .appendDefines(ctx);
    }

    public static String assembleFragment(boolean crumbling) {
        return assembleFragment(crumbling, StandardMaterialShaders.DEFAULT.fragmentSource(), ShaderAssembly.NO_EXTRA);
    }

    public static String assembleFragment(boolean crumbling, Identifier materialFragment, Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(new RawSource("meshvisual/frag_prelude", FRAG_PRELUDE));
        roots.add(FlwPrograms.SOURCES.get(MATERIAL));
        roots.add(FlwPrograms.SOURCES.get(PACKED_MATERIAL));
        roots.add(FlwPrograms.SOURCES.get(DIFFUSE));
        roots.add(FlwPrograms.SOURCES.get(RenderPassShaders.COLORIZER));
        roots.add(FlwPrograms.SOURCES.get(INDIRECT_LIGHT));
        if (!crumbling) {
            roots.add(new UberMaterialShaderComponent(FlwPrograms.SOURCES));
        }
        roots.add(FlwPrograms.SOURCES.get(materialFragment));
        roots.add(FlwPrograms.SOURCES.get(MV_FRAGMENT));
        return assembleFragment(ctx -> {
            if (crumbling) {
                ctx.define("_FLW_CRUMBLING");
            }
            commonFragTail(ctx, extra);
        }, roots);
    }

    private static void commonFragTail(Compilation ctx, Consumer<Compilation> extra) {
        ctx.mojImport("minecraft:fog.glsl");
        ctx.mojImport("minecraft:dynamictransforms.glsl");
        fragLightDefines(ctx);
        extra.accept(ctx);
    }

    public static String assembleOitFragment(OitMode mode, Identifier materialFragment, boolean localRead,
                                             Consumer<Compilation> extra) {
        List<SourceComponent> roots = List.of(
                new RawSource("meshvisual/frag_prelude", FRAG_PRELUDE),
                FlwPrograms.SOURCES.get(MATERIAL),
                FlwPrograms.SOURCES.get(PACKED_MATERIAL),
                FlwPrograms.SOURCES.get(DIFFUSE),
                FlwPrograms.SOURCES.get(RenderPassShaders.COLORIZER),
                FlwPrograms.SOURCES.get(INDIRECT_LIGHT),
                new UberMaterialShaderComponent(FlwPrograms.SOURCES),
                FlwPrograms.SOURCES.get(materialFragment),
                FlwPrograms.SOURCES.get(WAVELET),
                FlwPrograms.SOURCES.get(MV_OIT_FRAGMENT));
        return assembleFragment(ctx -> {
            ctx.define(mode.define);
            if (OitConfig.coefficientArray()) {
                ctx.define("_FLW_COEFF_ARRAY");
            }
            if (localRead) {
                ctx.define("_FLW_OIT_LOCAL_READ");
            }
            commonFragTail(ctx, extra);
        }, roots);
    }

    public static String assembleMlabOitFragment(OitInsertMode oitMode, Identifier materialFragment,
                                                 Consumer<Compilation> extra) {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(RenderPassShaders.MLAB),
                new RawSource("meshvisual/frag_prelude", FRAG_PRELUDE),
                FlwPrograms.SOURCES.get(MATERIAL),
                FlwPrograms.SOURCES.get(PACKED_MATERIAL),
                FlwPrograms.SOURCES.get(DIFFUSE),
                FlwPrograms.SOURCES.get(RenderPassShaders.COLORIZER),
                FlwPrograms.SOURCES.get(INDIRECT_LIGHT),
                new UberMaterialShaderComponent(FlwPrograms.SOURCES),
                FlwPrograms.SOURCES.get(materialFragment),
                FlwPrograms.SOURCES.get(MV_OIT_FRAGMENT));
        return assembleFragment(ctx -> {
            RenderPassShaders.mlabProducerDefines(ctx, oitMode);
            commonFragTail(ctx, extra);
        }, roots);
    }

    public static String assembleCommandBuilder() {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(BUFFER_BINDINGS),
                FlwPrograms.SOURCES.get(DRAW_COMMAND),
                FlwPrograms.SOURCES.get(MV_COMMAND_BUILDER));
        return assembleRaw(ctx -> {
        }, roots);
    }

}
