package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GuestShaderTemplates;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.ShaderCompatibility;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

import java.util.HashSet;
import java.util.Set;

final class GuestContractShaders {
    static final String VARYINGS = """
            ClrwlVertexData {
                vec4 flw_vertexPos;
                vec4 flw_vertexColor;
                vec2 flw_vertexTexCoord;
                flat ivec2 flw_vertexOverlay;
                vec2 flw_vertexLight;
                vec3 flw_vertexNormal;
                vec4 clrwl_vertexTangent;
                vec3 _flw_clipSlidPos;
                flat vec4 _flw_clipPlane;
            };
            """;

    private GuestContractShaders() {
    }

    static String vertexLibrary(ResourceLocation material) {
        return vertexLibrary(material, null);
    }

    static String vertexLibrary(ResourceLocation material, ResourceLocation light) {
        var result = new StringBuilder();
        var seen = new HashSet<String>();
        append(result, seen, ResourceUtil.rl("internal/material.glsl"),
                ResourceUtil.rl("internal/packed_material.glsl"),
                ResourceUtil.rl("internal/api_impl.glsl"), ResourceUtil.rl("internal/uniforms/uniforms.glsl"));
        result.append("\nFlwMaterial flw_material;\nuniform uvec2 _flw_packedMaterial;\n")
              .append("#ifdef FLW_EMBEDDED\nmat4 _flw_modelMatrix;\nmat3 _flw_normalMatrix;\n#endif\n");
        append(result, seen, material);
        if (light != null) {
            var defines = new Compilation();
            FlwConfig.INSTANCE.lightSmoothness().onCompile(defines);
            result.append(defines.assembledSource())
                  .append("vec4 flw_fragColor;\nvec2 flw_fragLight;\nuniform bool _flw_separateAo;\n")
                  .append("void flw_applyAo(float ao) { flw_fragColor.rgb*=ao; }\n");
            append(result, seen, ResourceUtil.rl("internal/instancing/light.glsl"), light);
            result.append("""
                    void _flw_guestVertexLight() {
                        flw_fragColor=vec4(1.0);
                        flw_fragLight=flw_vertexLight;
                        flw_shaderLight();
                        if (!flw_material.useLight) flw_fragLight=vec2(240.0/256.0);
                        flw_vertexLight=flw_fragLight;
                        if (_flw_separateAo) flw_vertexColor.a*=flw_fragColor.r;
                        else flw_vertexColor.rgb*=flw_fragColor.rgb;
                    }
                    """);
        }
        return result.toString();
    }

    static String fragmentLibrary(ResourceLocation material, ResourceLocation light, ResourceLocation cutout) {
        var result = new StringBuilder("in " + VARYINGS);
        var seen = new HashSet<String>();
        var defines = new Compilation();
        FlwConfig.INSTANCE.lightSmoothness().onCompile(defines);
        result.append(defines.assembledSource());
        append(result, seen, ResourceUtil.rl("internal/material.glsl"),
                ResourceUtil.rl("internal/packed_material.glsl"), ResourceUtil.rl("internal/api_impl.glsl"),
                ResourceUtil.rl("internal/uniforms/uniforms.glsl"), ResourceUtil.rl("internal/diffuse.glsl"));
        result.append("""
                uniform uvec2 _flw_packedMaterial;
                uniform sampler2D flw_diffuseTex;
                uniform sampler2D flw_overlayTex;
                uniform sampler2D flw_lightTex;
                uniform bool _flw_packOldLighting;
                FlwMaterial flw_material;
                vec4 flw_sampleColor;
                vec4 flw_fragColor;
                ivec2 flw_fragOverlay;
                vec2 flw_fragLight;
                bool flw_fragDiffuse;
                float flw_distance;
                float _flw_appliedAo;
                bool _flw_trackedAo;
                void flw_applyAo(float ao) {
                    _flw_appliedAo*=ao;
                    _flw_trackedAo=true;
                    flw_fragColor.rgb*=ao;
                }
                """);
        if (light == null) result.append("void flw_shaderLight() {}\n");
        else append(result, seen, ResourceUtil.rl("internal/instancing/light.glsl"), light);
        append(result, seen, material, cutout, ResourceUtil.rl("shaderpack/guest_contract.glsl"));
        return result.toString();
    }

    static String fragment(String pack, ResourceLocation material, ResourceLocation light, ResourceLocation cutout,
                           boolean embedded, boolean borrowed, boolean oit, boolean fog, boolean additiveOne) {
        return fragment(pack, material, light, cutout, embedded, borrowed, oit, fog, additiveOne, false, false);
    }

    static String damageFragment(String pack, ResourceLocation material, ResourceLocation light,
                                 ResourceLocation cutout,
                                 boolean embedded, boolean borrowed, boolean nativeDamage) {
        return fragment(pack, material, light, cutout, embedded, borrowed, false, false, false, true, nativeDamage);
    }

    private static String fragment(String pack, ResourceLocation material, ResourceLocation light,
                                   ResourceLocation cutout,
                                   boolean embedded, boolean borrowed, boolean oit, boolean fog, boolean additiveOne,
                                   boolean crumbling, boolean nativeDamage) {
        var result = new StringBuilder(GuestShaderTemplates.HEADER);
        GuestShaderTemplates.copyExtensions(pack, result);
        if (embedded) result.append("#define FLW_EMBEDDED\n");
        if (borrowed) result.append("#define _FLW_GUEST_BORROWED_BLOCK\n");
        if (oit) result.append("#define _FLW_GUEST_MAIN_OIT\n");
        if (!fog) result.append("#define _FLW_GUEST_FOG_NONE\n");
        if (additiveOne) result.append("#define _FLW_GUEST_ADDITIVE_ONE\n");
        if (crumbling) result.append("#define _FLW_GUEST_CRUMBLING\n");
        result.append(fragmentLibrary(material, light, cutout));
        var body = GuestShaderTemplates.stripPackDirectives(pack);
        if (nativeDamage) {
            var ast = GlslAst.parse(body);
            ast.renameFunction("main", "_flw_nativeDamageMain");
            body = ast.body();
            body += """
                    void main() {
                        vec4 c,o; vec2 light; float ao;
                        clrwl_computeFragment(vec4(1.0),c,light,ao,o);
                        _flw_nativeDamageMain();
                    }
                    """;
        }
        result.append(body);
        return ShaderCompatibility.normalize(result.toString(), GL20.GL_FRAGMENT_SHADER);
    }

    static String terrainVertex(String pack) {
        var result = new StringBuilder(GuestShaderTemplates.HEADER);
        GuestShaderTemplates.copyExtensions(pack, result);
        result.append("invariant gl_Position;\nout ").append(VARYINGS)
              .append("uniform mat4 _flw_packViewInverse;\nuniform vec3 _flw_originRelToCameraRel;\n");
        result.append(vertexLibrary(ResourceUtil.rl("material/default.vert")));
        var body = GuestShaderTemplates.stripPackDirectives(pack);
        var ast = GlslAst.parse(body);
        ast.renameFunction("main", "_flw_packMain");
        result.append(ast.body());
        result.append("""
                void main() {
                    _flw_unpackMaterialProperties(_flw_packedMaterial.y,flw_material);
                    vec4 cameraPosition=_flw_packViewInverse*gl_ModelViewMatrix*gl_Vertex;
                    flw_vertexPos=cameraPosition-vec4(_flw_originRelToCameraRel,0.0);
                    flw_vertexColor=gl_Color;
                    flw_vertexTexCoord=gl_MultiTexCoord0.xy;
                    flw_vertexLight=gl_MultiTexCoord1.xy/256.0;
                    flw_vertexOverlay=ivec2(0,10);
                    flw_vertexNormal=normalize(mat3(_flw_packViewInverse)*gl_NormalMatrix*gl_Normal);
                    vec3 reference=abs(flw_vertexNormal.x)>0.9?vec3(0.0,0.0,1.0):vec3(1.0,0.0,0.0);
                    clrwl_vertexTangent=vec4(normalize(cross(flw_vertexNormal,reference)),1.0);
                    _flw_clipSlidPos=vec3(0.0);
                    _flw_clipPlane=vec4(0.0);
                    _flw_packMain();
                }
                """);
        return ShaderCompatibility.normalize(result.toString(), GL20.GL_VERTEX_SHADER);
    }

    static String varyingBlock(String name) {
        var ast = GlslAst.preprocessed("out " + VARYINGS);
        var block = ast.nodes(InterfaceBlockDeclaration.class).findFirst().orElseThrow();
        return name + " " + GlslAst.print(block.getStructBody());
    }

    static String geometry(String pack) {
        var result = new StringBuilder(GuestShaderTemplates.HEADER);
        GuestShaderTemplates.copyExtensions(pack, result);
        var block = varyingBlock("ClrwlVertexData");
        result.append("in ").append(block).append(" clrwl_in[];\nout ").append(block).append(" clrwl_out;\n")
              .append("void clrwl_setVertexOut(int i) {\n");
        for (var field : new String[]{"flw_vertexPos", "flw_vertexColor", "flw_vertexTexCoord", "flw_vertexOverlay",
                "flw_vertexLight", "flw_vertexNormal", "clrwl_vertexTangent", "_flw_clipSlidPos", "_flw_clipPlane"}) {
            result.append("clrwl_out.").append(field).append("=clrwl_in[i].").append(field).append(";\n");
        }
        return ShaderCompatibility.normalize(
                result.append("}\n").append(GuestShaderTemplates.stripPackDirectives(pack)).toString(),
                GL32.GL_GEOMETRY_SHADER);
    }

    static String library(ResourceLocation... roots) {
        var result = new StringBuilder();
        var seen = new HashSet<String>();
        append(result, seen, roots);
        return result.toString();
    }

    private static void append(StringBuilder result, Set<String> seen, ResourceLocation... roots) {
        for (var root : roots) append(FlwPrograms.SOURCES.get(root), result, seen);
    }

    private static void append(SourceComponent component, StringBuilder result, Set<String> seen) {
        if (!seen.add(component.name())) return;
        for (var included : component.included()) append(included, result, seen);
        result.append(component.source()).append('\n');
    }
}
