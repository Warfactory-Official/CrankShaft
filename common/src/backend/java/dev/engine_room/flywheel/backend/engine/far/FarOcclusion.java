package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.indirect.DepthPyramid;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;


/**
 * Current-frame opaque terrain in the retained renderer's reversed-Z projection, reduced by the shared HiZ path.
 */
final class FarOcclusion {
    private final DepthPyramid pyramid = new DepthPyramid(IndirectPrograms.get());
    private final AsyncGlProgram mergeBuild;
    private final int sampler;
    private int width;
    private int height;

    FarOcclusion() {
        var compilation = new Compilation();
        compilation.version(GlslVersion.V430);
        compilation.appendComponent(new RawSource("far opaque depth", MERGE));
        mergeBuild = AsyncGlProgram.compute("far/opaque_depth", compilation, _ -> {
        });
        sampler = GL45C.glCreateSamplers();
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
    }

    boolean capture(FarDepth source, Matrix4fc projection) {
        var merge = mergeBuild.get();
        if (merge == null) return false;
        width = source.width();
        height = source.height();
        pyramid.prepare(width, height);
        merge.bind();
        merge.setMat4("terrainToFar", new Matrix4f(projection).mul(new Matrix4f(source.terrainProjection()).invert()));
        merge.setMat4("vanillaToFar", new Matrix4f(projection).mul(new Matrix4f(source.vanillaProjection()).invert()));
        merge.setVec2("vanillaScale", source.vanillaUScale(), source.vanillaVScale());
        merge.setVec2("sourceSize", width, height);
        merge.setInt("terrainZeroToOne", source.terrainZeroToOne() ? 1 : 0);
        merge.setInt("vanillaZeroToOne", source.vanillaZeroToOne() ? 1 : 0);
        merge.setBool("hasVanilla", source.vanillaDepth() != 0);
        merge.setFloat("terrainClear", source.terrainReverseZ() ? 0 : 1);
        merge.setFloat("vanillaClear", source.vanillaReverseZ() ? 0 : 1);
        bindTexture(11, source.terrainDepth());
        bindTexture(12, source.vanillaDepth());
        GL42C.glBindImageTexture(0, pyramid.pyramidTextureId, 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
        GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT);
        GL43C.glDispatchCompute((DepthPyramid.mip0Size(width) + 7) / 8,
                (DepthPyramid.mip0Size(height) + 7) / 8, 1);
        pyramid.reduce();
        return true;
    }

    int levels() {
        return DepthPyramid.getImageMipLevels(DepthPyramid.mip0Size(width), DepthPyramid.mip0Size(height)) - 1;
    }

    int texture() {
        return pyramid.pyramidTextureId;
    }

    void bind() {
        pyramid.bindForCull();
        GL33C.glBindSampler(10, sampler);
    }

    void delete() {
        pyramid.delete();
        mergeBuild.close();
        GL33C.glDeleteSamplers(sampler);
    }

    private void bindTexture(int unit, int texture) {
        if (unit < 12) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(texture);
        } else {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        }
        GL33C.glBindSampler(unit, sampler);
    }

    private static final String MERGE = """
            layout(local_size_x=8, local_size_y=8) in;
            layout(binding=11) uniform sampler2D terrainDepth;
            layout(binding=12) uniform sampler2D vanillaDepth;
            layout(binding=0, r32f) writeonly uniform image2D farDepth;
            uniform mat4 terrainToFar;
            uniform mat4 vanillaToFar;
            uniform vec2 vanillaScale;
            uniform vec2 sourceSize;
            uniform bool terrainZeroToOne;
            uniform bool vanillaZeroToOne;
            uniform bool hasVanilla;
            uniform float terrainClear;
            uniform float vanillaClear;
            float convertedDepth(ivec2 at) {
                if (any(greaterThanEqual(at, ivec2(sourceSize)))) return 0.0;
                vec2 uv = (vec2(at) + 0.5) / sourceSize;
                vec2 vanillaUv = uv * vanillaScale;
                float nearDepth = hasVanilla ? textureLod(vanillaDepth, vanillaUv, 0.0).r : vanillaClear;
                float lodDepth = texelFetch(terrainDepth, at, 0).r;
                float result = 0.0;
                if (nearDepth != vanillaClear) {
                    vec4 clip = vanillaToFar * vec4(uv * 2.0 - 1.0,
                            vanillaZeroToOne ? nearDepth : nearDepth * 2.0 - 1.0, 1.0);
                    result = clip.z / clip.w;
                } else if (lodDepth != terrainClear) {
                    vec4 clip = terrainToFar * vec4(uv * 2.0 - 1.0,
                            terrainZeroToOne ? lodDepth : lodDepth * 2.0 - 1.0, 1.0);
                    result = clip.z / clip.w;
                }
                return clamp(result, 0.0, 1.0);
            }
            void main() {
                ivec2 at = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(at, imageSize(farDepth)))) return;
                ivec2 base = at * 2;
                float depth00 = convertedDepth(base);
                float depth01 = convertedDepth(base + ivec2(0, 1));
                float depth10 = convertedDepth(base + ivec2(1, 0));
                float depth11 = convertedDepth(base + ivec2(1, 1));
                imageStore(farDepth, at, vec4(min(min(depth00, depth01), min(depth10, depth11))));
            }
            """;
}
