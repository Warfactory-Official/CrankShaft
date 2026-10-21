package dev.engine_room.flywheel.backend.engine.shaderpack;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.compile.component.BufferTextureInstanceComponent;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.TextureBinder;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GuestGlslRewriter;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GuestVertexWrapper;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslPreprocessor;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedLight;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GuestPrograms {
    private static final Material TERRAIN_MATERIAL = SimpleMaterial.builder().useOverlay(false).useLight(true)
                                                                   .cardinalLightingMode(CardinalLightingMode.OFF)
                                                                   .ambientOcclusion(false).build();
    private final Map<Key, Program> programs = new HashMap<>();
    private final Map<Key, String> rejectedOit = new HashMap<>();
    private Key lastKey;
    private Program lastProgram;
    private List<InstanceType<?>> generationTypes = List.of();
    private final Reference2ObjectArrayMap<InstanceType<?>, List<InstanceType<?>>> singleTypes = new Reference2ObjectArrayMap<>();
    private GuestHost owner;

    private static Program create(Key key, GuestRenderPass pass) {
        var sourceProgram = pass.program;
        var source = key.authored == null ? GlslPreprocessor.preprocess(
                GL20.glGetShaderSource(key.vertex)) : key.authored.vertex;
        var rewritten = GuestGlslRewriter.rewrite(source);
        var inputs = rewritten.variables();
        var vertexLight = key.authored == null && !key.shadow && !key.crumbling && switch (pass.role()) {
            case GBUFFERS_ARMOR_GLINT, GBUFFERS_SPIDEREYES, GBUFFERS_BEACONBEAM, GBUFFERS_LIGHTNING -> false;
            default -> true;
        };
        var type = key.types.getFirst();
        var vertex = GuestVertexWrapper.wrapVertex(key.indirect ? "" : new InstanceStructComponent(type).source(),
                key.indirect ? GuestInstanceShader.source(key.types) : new BufferTextureInstanceComponent(
                        type).source(),
                key.indirect ? "" : instanceSource(type),
                rewritten.source(), inputs, key.crumbling,
                (key.indirect ? GuestContractShaders.library(ResourceUtil.rl("shaderpack/indirect.glsl")) : "")
                        + GuestContractShaders.vertexLibrary(key.materialVertex, vertexLight ? key.light : null),
                key.authored == null && !key.crumbling ? null : GuestContractShaders.VARYINGS, key.embedded,
                vertexLight, key.indirect);
        var stages = stages(pass, vertex, key.authored, key.oit != null);
        vertex = stages.vertex();
        if (System.getProperty("flw.shaderpack.dumpSources") != null) {
            try {
                var directory = Minecraft.getMinecraft().gameDir.toPath().resolve("flywheel_sources/shaderpack");
                Files.createDirectories(directory);
                var name = sourceProgram + "-" + key.types.hashCode();
                Files.writeString(directory.resolve(name + "-native.vsh"), source);
                Files.writeString(directory.resolve(name + "-instance.vsh"), vertex);
                Files.writeString(directory.resolve(name + "-native.fsh"), GL20.glGetShaderSource(key.fragment));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        var shader = compile(GL20.GL_VERTEX_SHADER, vertex);
        var fragment = 0;
        try {
            String fragmentSource = null;
            if (key.crumbling) {
                fragmentSource = GuestContractShaders.damageFragment(
                        key.authored == null ? GL20.glGetShaderSource(key.fragment) : key.authored.fragment,
                        key.materialFragment, key.light, key.cutout, key.embedded, key.borrowedBlock,
                        key.authored == null);
            } else if (key.authored != null) {
                fragmentSource = GuestContractShaders.fragment(key.authored.fragment, key.materialFragment, key.light,
                        key.cutout, key.embedded, key.borrowedBlock, key.oit != null && !key.shadow,
                        !key.fog.equals(FogShaders.NONE.source()), key.additiveOne);
            }
            if (key.oit != null) fragmentSource = GuestShaders.fragment(
                    fragmentSource == null ? pass.host.fragment(pass) : fragmentSource,
                    new GuestShaders.OitSpec(key.oit, key.contract,
                            key.authored == null ? key.contract.drawBuffers() : key.authored.drawBuffers, key.shadow),
                    pass.host);
            if (key.crumbling && System.getProperty("flw.shaderpack.dumpSources") != null) {
                try {
                    Files.writeString(Minecraft.getMinecraft().gameDir.toPath().resolve(
                            "flywheel_sources/shaderpack/" + sourceProgram + "-damage.fsh"), fragmentSource);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }
            fragment = fragmentSource == null ? key.fragment : compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
            var result = linked(shader, fragment, stages, pass, true, key.indirect);
            result.authored = key.authored;
            result.deferredMaterial = !key.shadow && pass.host.deferredReplay() && key.authored != null
                    && key.authored.program == ContractProgram.GBUFFERS_TRANSLUCENT;
            result.oit = key.oit != null;
            result.crumbling = key.crumbling;
            result.indirect = key.indirect;
            // Native damage programs can store metadata in alpha; their pass owns the fixed-function alpha test.
            result.nativeCutout = key.crumbling ? Float.NaN : nativeCutout(key.cutout);
            return result;
        } finally {
            GL20.glDeleteShader(shader);
            if ((key.oit != null || key.authored != null || key.crumbling) && fragment != 0)
                GL20.glDeleteShader(fragment);
        }
    }

    static Program terrain(GuestRenderPass pass, GuestShaders.OitSpec spec) {
        return terrain(pass, spec, false);
    }

    static Program terrain(GuestRenderPass pass, GuestShaders.OitSpec spec, boolean indirect) {
        var authored = spec == null ? null : pass.host.contracts().translucent(pass.shadow);
        var sourceVertex = authored == null ? GL20.glGetShaderSource(pass.vertexShader) : authored.vertex;
        var stages = stages(pass, indirect ? GuestTerrainShaders.vertex(sourceVertex, authored != null)
                        : authored == null ? sourceVertex : GuestContractShaders.terrainVertex(sourceVertex), authored,
                spec != null);
        var vertex = compile(GL20.GL_VERTEX_SHADER, stages.vertex());
        var fragment = 0;
        try {
            var source = authored == null ? pass.host.fragment(pass) : GuestContractShaders.fragment(
                    authored.fragment, TERRAIN_MATERIAL.shaders().fragmentSource(), null,
                    TERRAIN_MATERIAL.cutout().source(), false, true, !pass.shadow, true, false);
            fragment = spec == null ? pass.fragmentShader : compile(GL20.GL_FRAGMENT_SHADER,
                    GuestShaders.fragment(source, spec, pass.host));
            var result = linked(vertex, fragment, stages, pass, false, indirect);
            result.oit = spec != null;
            result.authored = authored;
            result.fixedMaterial = authored == null ? null : TERRAIN_MATERIAL;
            return result;
        } finally {
            GL20.glDeleteShader(vertex);
            if (spec != null && fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static GuestTessellation.Stages stages(GuestRenderPass pass, String vertex, ContractProgramSet.Source authored,
                                                   boolean oit) {
        var stages = GuestTessellation.prepare(vertex,
                authored != null ? authored.control : source(pass.tessControlShader),
                authored != null ? authored.evaluation : source(pass.tessEvaluationShader),
                authored != null ? authored.geometry : source(pass.geometryShader), authored != null);
        if (oit) {
            pass.host.checkStageEffects(stages.vertex());
            pass.host.checkStageEffects(stages.control());
            pass.host.checkStageEffects(stages.evaluation());
            pass.host.checkStageEffects(stages.geometry());
        }
        return stages;
    }

    private static String source(int shader) {
        return shader == 0 ? null : GL20.glGetShaderSource(shader);
    }

    private static Program linked(int vertex, int fragment, GuestTessellation.Stages stages, GuestRenderPass pass,
                                  boolean instanced, boolean indirect) {
        var shaders = new int[]{vertex, fragment, 0, 0, 0};
        try {
            if (stages.geometry() != null) shaders[2] = compile(GL32.GL_GEOMETRY_SHADER, stages.geometry());
            if (stages.control() != null) shaders[3] = compile(GL40.GL_TESS_CONTROL_SHADER, stages.control());
            if (stages.evaluation() != null) shaders[4] = compile(GL40.GL_TESS_EVALUATION_SHADER, stages.evaluation());
            var nativeProgram = pass.host.createProgram(pass.role());
            var handle = nativeProgram.getId();
            link(handle, shaders, pass.program, instanced);
            try {
                return new Program(nativeProgram, pass, indirect, stages.evaluation() != null);
            } catch (RuntimeException | Error error) {
                GL20.glDeleteProgram(handle);
                throw error;
            }
        } finally {
            for (var index = 2; index < shaders.length; index++)
                if (shaders[index] != 0) GL20.glDeleteShader(shaders[index]);
        }
    }

    private static int link(int vertex, int fragment, int source, boolean instanced) {
        var handle = GL20.glCreateProgram();
        link(handle, vertex, fragment, 0, source, instanced);
        return handle;
    }

    private static void link(int handle, int vertex, int fragment, int geometry, int source, boolean instanced) {
        link(handle, new int[]{vertex, fragment, geometry}, source, instanced);
    }

    private static void link(int handle, int[] shaders, int source, boolean instanced) {
        try (var stack = MemoryStack.stackPush()) {
            for (var shader : shaders) if (shader != 0) GL20.glAttachShader(handle, shader);
            if (instanced) {
                var attributes = new String[]{"_flw_aPos", "_flw_aColor", "_flw_aTexCoord", "_flw_aOverlay",
                        "_flw_aLight", "_flw_aNormal", "_flw_aMidTexCoord", "_flw_aIrisEntity", "_flw_aTangent", "_flw_aMidBlock", "_flw_aFaceNormal"};
                for (var index = 0; index < attributes.length; index++)
                    GL20.glBindAttribLocation(handle, index, attributes[index]);
            } else if (source != 0) {
                GL20.glBindAttribLocation(handle, 11, "mc_Entity");
                GL20.glBindAttribLocation(handle, 12, "mc_midTexCoord");
                GL20.glBindAttribLocation(handle, 13, "at_tangent");
                GL20.glBindAttribLocation(handle, 14, "at_midBlock");
                GL20.glBindAttribLocation(handle, 4, "ausm_ChunkOffsetInstanced");
                var size = stack.mallocInt(1);
                var type = stack.mallocInt(1);
                var count = GL20.glGetProgrami(source, GL20.GL_ACTIVE_ATTRIBUTES);
                var length = GL20.glGetProgrami(source, GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH);
                for (var index = 0; index < count; index++) {
                    var name = GL20.glGetActiveAttrib(source, index, length, size, type);
                    // 1.12.2: compatibility built-ins have reserved locations and cannot be rebound.
                    if (name.startsWith("gl_") || name.equals("mc_Entity") || name.equals("mc_midTexCoord")
                            || name.equals("at_tangent") || name.equals("at_midBlock")) continue;
                    GL20.glBindAttribLocation(handle, GL20.glGetAttribLocation(source, name), name);
                }
            }
            GL20.glLinkProgram(handle);
            if (GL20.glGetProgrami(handle, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
                throw new IllegalStateException("Shader host guest program: " + GL20.glGetProgramInfoLog(handle));
            }
        } catch (RuntimeException | Error error) {
            GL20.glDeleteProgram(handle);
            throw error;
        }
    }

    static GlProgram fullscreen(String fragmentSource) {
        return graphics(GuestOitCodegen.COMPOSITE_VERTEX, fragmentSource);
    }

    static GlProgram graphics(String vertexSource, String fragmentSource) {
        var vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
        var fragment = 0;
        try {
            fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
            return new GlProgram(link(vertex, fragment, 0, false));
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    public static int compile(int type, String source) {
        var shader = GL20.glCreateShader(type);
        GlCompat.safeShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            var log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            var failure = new IllegalStateException("Shader host shader: " + log);
            try {
                var directory = Minecraft.getMinecraft().gameDir.toPath().resolve("flywheel_sources/shaderpack");
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(type + "-failed.glsl"), source);
            } catch (IOException error) {
                failure.addSuppressed(error);
            }
            throw failure;
        }
        return shader;
    }

    public static String instanceSource(InstanceType<?> type) {
        return GuestContractShaders.library(type.vertexShader());
    }

    static GlProgram compute(String source) {
        var shader = compile(GL43.GL_COMPUTE_SHADER, source);
        var handle = GL20.glCreateProgram();
        GL20.glAttachShader(handle, shader);
        GL20.glLinkProgram(handle);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(handle, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            var log = GL20.glGetProgramInfoLog(handle);
            GL20.glDeleteProgram(handle);
            throw new IllegalStateException("Guest compute program: " + log);
        }
        return new GlProgram(handle);
    }

    private static float nativeCutout(ResourceLocation cutout) {
        if (cutout.equals(CutoutShaders.EPSILON.source())) return 0;
        if (cutout.equals(CutoutShaders.ONE_TENTH.source())) return 0.1f;
        if (cutout.equals(CutoutShaders.HALF.source())) return 0.5f;
        return -1;
    }

    public Program get(GuestRenderPass pass, GuestDraw draw, boolean crumbling) {
        return get(pass, draw, crumbling, null, null);
    }

    Program get(GuestRenderPass pass, GuestDraw draw, OitPass oit, GuestOitContract contract) {
        return get(pass, draw, false, oit, contract);
    }

    private Program get(GuestRenderPass pass, GuestDraw draw, boolean crumbling, OitPass oit, GuestOitContract contract) {
        own(pass.host);
        var material = ((GuestDraw) draw).material(crumbling);
        var kind = ((GuestDraw) draw).kind(pass.host);
        var types = draw.indirect() ? InstanceTypeIds.snapshot().types() : singleType(draw.groupKey().instanceType());
        if (draw.indirect() && generationTypes != types) {
            programs.entrySet().removeIf(entry -> {
                if (!entry.getKey().indirect) return false;
                entry.getValue().program.delete();
                return true;
            });
            rejectedOit.keySet().removeIf(key -> key.indirect);
            lastKey = null;
            lastProgram = null;
            generationTypes = types;
        }
        var key = new Key(pass.program, pass.vertexShader, pass.fragmentShader, pass.geometryShader,
                pass.tessControlShader, pass.tessEvaluationShader,
                types, pass.shadow,
                crumbling, oit, contract, material.shaders().vertexSource(), material.shaders().fragmentSource(),
                material.light().source(), material.cutout().source(), material.fog().source(),
                draw.groupKey().environment().contextShader() == ContextShader.EMBEDDED,
                pass.host.contracts().resolve(pass, material, crumbling, kind, oit != null),
                material.transparency() == Transparency.ADDITIVE, kind == TaggedEnvironment.KIND_BORROWED_BLOCK,
                draw.indirect());
        if (key.equals(lastKey)) return lastProgram;
        var rejected = rejectedOit.get(key);
        if (rejected != null) throw new UnsupportedOperationException(rejected);
        try {
            lastProgram = programs.get(key);
            if (lastProgram == null) {
                lastProgram = create(key, pass);
                programs.put(key, lastProgram);
            }
        } catch (UnsupportedOperationException error) {
            if (oit != null) rejectedOit.put(key, error.getMessage());
            throw error;
        }
        lastKey = key;
        return lastProgram;
    }

    private List<InstanceType<?>> singleType(InstanceType<?> type) {
        var types = singleTypes.get(type);
        if (types == null) {
            types = List.of(type);
            singleTypes.put(type, types);
        }
        return types;
    }

    void own(GuestHost host) {
        if (owner == host) return;
        delete();
        owner = host;
    }

    public void delete() {
        programs.values().forEach(program -> program.program.delete());
        programs.clear();
        rejectedOit.clear();
        singleTypes.clear();
        lastKey = null;
        lastProgram = null;
    }

    boolean authoredContractActive() {
        return programs.values().stream().anyMatch(program -> program.authored != null && program.submittedDraws > 0);
    }

    private record Key(int source, int vertex, int fragment, int geometry, int control, int evaluation,
                       List<InstanceType<?>> types, boolean shadow, boolean crumbling,
                       OitPass oit, GuestOitContract contract, ResourceLocation materialVertex,
                       ResourceLocation materialFragment,
                       ResourceLocation light, ResourceLocation cutout, ResourceLocation fog, boolean embedded,
                       ContractProgramSet.Source authored, boolean additiveOne, boolean borrowedBlock,
                       boolean indirect) {
    }

    public static final class Program {
        public final GlProgram program;
        public final int instanceUnit;
        public final boolean needsMidUv;
        final boolean tessellated;
        private final GuestSamplerUnits uniforms;
        private final GuestProgramHandle nativeProgram;
        private final int lightLutUnit;
        private final int lightSectionsUnit;
        private final int overlayUnit;
        private final int lightmapUnit;
        private final int diffuseUnit;
        private ContractProgramSet.Source authored;
        private boolean oit;
        private boolean crumbling;
        private boolean indirect;
        private boolean deferredMaterial;
        private float nativeCutout = Float.NaN;
        private long submittedDraws;
        private Material fixedMaterial;
        private int uploadedTag;
        private int uploadedItemTag;
        private GuestRenderPass uploaded;
        private int uploadedEpoch;

        Program(GuestProgramHandle nativeProgram, GuestRenderPass pass, boolean indirect, boolean tessellated) {
            this.nativeProgram = nativeProgram;
            this.tessellated = tessellated;
            this.indirect = indirect;
            var handle = nativeProgram.getId();
            program = new GlProgram(handle);
            if (indirect) pass.host.storage.configure(program);
            program.bind();
            pass.host.bindResources(pass.role(), nativeProgram, null, Float.NaN);
            uniforms = pass.host.samplers;
            uniforms.capture(handle);
            instanceUnit = program.getUniformLocation("_flw_instances") >= 0 ? uniforms.instanceSampler() : -1;
            lightLutUnit = privateSampler("_flw_lightLut");
            lightSectionsUnit = privateSampler("_flw_lightSections");
            overlayUnit = privateSampler("flw_overlayTex");
            lightmapUnit = privateSampler("flw_lightTex");
            diffuseUnit = privateSampler("flw_diffuseTex");
            Uniforms.setUniformBlockBindings(program);
            needsMidUv = GL20.glGetAttribLocation(handle, "_flw_aMidTexCoord") >= 0;
        }

        int[] guestSamplers(int count) {
            return uniforms.guestSamplers(count);
        }

        private int privateSampler(String name) {
            return program.getUniformLocation(name) < 0 ? -1 : uniforms.reserve(name);
        }

        void bindDraw(GuestRenderPass pass, GuestDraw draw, InstancedLight light, int instance) {
            var guest = (GuestDraw) draw;
            bind(pass, instance, guest);
            var material = material(draw);
            program.setUVec2("_flw_packedMaterial", 0, MaterialEncoder.packProperties(material));
            if (!indirect) program.setUInt("_flw_baseVertex", draw.mesh().baseVertex());
            var tag = guest.tag(pass.host);
            GL20.glUniform4i(program.getUniformLocation("_flw_overrideMaterial"),
                    TaggedEnvironment.kind(tag) == TaggedEnvironment.KIND_BORROWED_BLOCK ? TaggedEnvironment.id(
                            tag) : -1,
                    guest.metadata().borrowedRenderType, guest.metadata().borrowedMetadata,
                    guest.metadata().borrowedEmission);
            program.setInt("_flw_embedded",
                    draw.groupKey().environment().contextShader() == ContextShader.EMBEDDED ? 1 : 0);
            if (authored != null && !oit) {
                GlStateManager.disableAlpha();
                MaterialRenderState.setup(material);
                pass.targets(authored.drawBuffers);
                var defaultBlend = ContractProperties.Blend.material(material.transparency());
                var properties = pass.host.contracts().properties;
                for (var slot = 0; slot < authored.drawBuffers.length; slot++) {
                    properties.blend(authored.program, authored.drawBuffers[slot], defaultBlend).apply(slot);
                }
                if (authored.program == ContractProgram.GBUFFERS_ENTITIES) GlStateManager.depthMask(true);
            } else {
                if (!oit) {
                    pass.materialBlend(material);
                    if (!Float.isNaN(nativeCutout)) {
                        if (nativeCutout < 0) GlStateManager.disableAlpha();
                        else {
                            GlStateManager.enableAlpha();
                            GlStateManager.alphaFunc(GL11.GL_GREATER, nativeCutout);
                        }
                    }
                }
                if (!Float.isNaN(nativeCutout)) {
                    program.setInt("_flw_nativeAlphaFunction", nativeCutout < 0 ? GL11.GL_ALWAYS : GL11.GL_GREATER);
                    program.setFloat("_flw_nativeAlphaReference", Math.max(0, nativeCutout));
                }
            }
            var active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            var lightmap = GlStateManager.textureState[OpenGlHelper.lightmapTexUnit - GL13.GL_TEXTURE0].textureName;
            var diffuse = GlStateManager.textureState[0].textureName;
            light.bind(lightLutUnit, lightSectionsUnit);
            GuestSamplerUnits.bindTexture(diffuseUnit, GL11.GL_TEXTURE_2D, diffuse);
            if (overlayUnit >= 0)
                GuestSamplerUnits.bindTexture(overlayUnit, GL11.GL_TEXTURE_2D, TextureBinder.overlayTextureId());
            GuestSamplerUnits.bindTexture(lightmapUnit, GL11.GL_TEXTURE_2D, lightmap);
            GlStateTracker.hostActiveTexture(active);
            if (!indirect) draw.groupKey().environment().setupDraw(program);
            if (deferredMaterial) GlStateManager.depthMask(true);
            submittedDraws++;
        }

        Material material(GuestDraw draw) {
            return ((GuestDraw) draw).material(crumbling);
        }

        public void bind(GuestRenderPass pass, int instance) {
            bind(pass, instance, null);
        }

        private void bind(GuestRenderPass pass, int instance, GuestDraw draw) {
            pass.primitive(tessellated);
            program.bind();
            var tag = draw == null ? 0 : draw.tag(pass.host);
            var itemTag = draw == null ? 0 : draw.itemTag(pass.host);
            var frame = uploaded != pass || uploadedEpoch != pass.epoch;
            if (frame || tag != uploadedTag || itemTag != uploadedItemTag) {
                pass.host.bindResources(pass.role(), nativeProgram, draw,
                        authored != null ? 0 : Float.isNaN(nativeCutout) ? Float.NaN : Math.max(0, nativeCutout));
                if (indirect) pass.host.storage.bind();
                uploadedTag = tag;
                uploadedItemTag = itemTag;
            }
            if (frame) {
                pass.upload(program);
                program.setVec3("ausm_ChunkOffset", 0, 0, 0);
                program.setFloat("mc_chunkFade", 1);
                GL20.glUniform1i(program.getUniformLocation("_flw_instances"), instanceUnit);
                program.setInt("_flw_lightLut", lightLutUnit);
                program.setInt("_flw_lightSections", lightSectionsUnit);
                program.setInt("flw_overlayTex", overlayUnit);
                program.setInt("flw_lightTex", lightmapUnit);
                program.setInt("flw_diffuseTex", diffuseUnit);
                program.setInt("_flw_packOldLighting", pass.host.settings().oldLighting() ? 1 : 0);
                program.setInt("_flw_separateAo", pass.host.settings().separateAo() ? 1 : 0);
                if (authored != null) program.setInt("_flw_nativeAlphaFunction", GL11.GL_ALWAYS);
                uploaded = pass;
                uploadedEpoch = pass.epoch;
            }
            if (!indirect) program.setInt("_flw_baseInstance", instance);
            if (fixedMaterial != null)
                program.setUVec2("_flw_packedMaterial", 0, MaterialEncoder.packProperties(fixedMaterial));
        }

        int primitive() {
            return tessellated ? GL40.GL_PATCHES : GL11.GL_TRIANGLES;
        }

        public void activateInstances() {
            // TODO (ARB_bindless_texture): Add bindless_sampler variants and immutable per-slab views to avoid TexBuffer binds.
            // Recreate views on slab growth; handled textures cannot be retargeted, and residency retirement needs a fence.
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + instanceUnit);
        }

        public void finishInstances() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GlStateManager.activeTextureUnit);
        }
    }
}
