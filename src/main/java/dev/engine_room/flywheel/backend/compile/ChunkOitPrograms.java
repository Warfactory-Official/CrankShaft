package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.core.CompilationHarness;
import dev.engine_room.flywheel.backend.compile.core.Compile;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import dev.engine_room.flywheel.backend.glsl.GlslProfile;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL43;

import static org.lwjgl.opengl.GL13.GL_TEXTURE0;

/**
 * One program per {@link PipelineCompiler.OitMode} drawing vanilla chunk-translucent VBOs into
 * the OIT framebuffer.
 */
public final class ChunkOitPrograms {
    private static final ResourceLocation VERTEX_SHADER = ResourceUtil.rl("internal/chunk_oit.vert");
    private static final ResourceLocation FRAGMENT_SHADER = ResourceUtil.rl("internal/chunk_oit.frag");

    private static final Compile<PipelineCompiler.OitMode> COMPILE = new Compile<>();

    private final CompilationHarness<PipelineCompiler.OitMode> harness;

    private ChunkOitPrograms(CompilationHarness<PipelineCompiler.OitMode> harness) {
        this.harness = harness;
    }

    /**
     * Compiles all non-OFF modes eagerly so failures surface at reload time. The harness keeps the
     * results cached for subsequent {@link #get} calls.
     */
    public static ChunkOitPrograms create(ShaderSources sources) {
        // Compat profile (NOT core) for gl_ModelViewProjectionMatrix / gl_TextureMatrix[N]; the
        // shaders ride vanilla MC's GL_MODELVIEW matrix stack rather than uploading per-chunk MVP.
        // GPU's MAX version for parity with all other compile sites — compat is backwards-compatible
        // so any GL ≥ 3.3 desktop driver accepts it.
        var harness = COMPILE.program()
                .link(COMPILE.shader(GlCompat.MAX_GLSL_VERSION, GlslProfile.COMPATIBILITY, ShaderType.VERTEX)
                        .nameMapper(mode -> "chunk_oit/vert" + mode.name)
                        .onCompile(ChunkOitPrograms::defineMode)
                        .withResource(VERTEX_SHADER))
                .link(COMPILE.shader(GlCompat.MAX_GLSL_VERSION, GlslProfile.COMPATIBILITY, ShaderType.FRAGMENT)
                        .nameMapper(mode -> "chunk_oit/frag" + mode.name)
                        .onCompile(ChunkOitPrograms::defineMode)
                        .withResource(FRAGMENT_SHADER))
                .postLink((mode, program) -> {
                    program.bind();
                    Uniforms.setUniformBlockBindings(program);
                    // Vanilla chunk renderer leaves block atlas on T0 and lightmap on
                    // OpenGlHelper.lightmapTexUnit; sampler bindings have to match those exact units.
                    program.setSamplerBinding("_flw_atlas", GlTextureUnit.T0);
                    program.setSamplerBinding("_flw_lightmap", lightmapUnit());
                    program.setSamplerBinding("_flw_depthRange", Samplers.DEPTH_RANGE);
                    program.setSamplerBinding("_flw_coefficients", Samplers.COEFFICIENTS);
                    program.setSamplerBinding("_flw_blueNoise", Samplers.NOISE);
                    GlProgram.unbind();
                    GL43.glObjectLabel(GL43.GL_PROGRAM, program.handle(), "crankshaft.chunk_oit." + mode.name);
                })
                .harness("chunk_oit", sources);
        var programs = new ChunkOitPrograms(harness);
        for (PipelineCompiler.OitMode mode : PipelineCompiler.OitMode.values()) {
            if (mode != PipelineCompiler.OitMode.OFF) {
                harness.get(mode);
            }
        }
        return programs;
    }

    public GlProgram get(PipelineCompiler.OitMode mode) {
        return harness.get(mode);
    }

    public void delete() {
        harness.delete();
    }

    private static void defineMode(PipelineCompiler.OitMode mode, Compilation ctx) {
        if (!mode.define.isEmpty()) {
            ctx.define(mode.define);
        }
        ctx.polyfillFmaIfMissing();
    }

    private static GlTextureUnit lightmapUnit() {
        // OpenGlHelper.lightmapTexUnit is GL_TEXTUREN; subtract GL_TEXTURE0 to get the unit index.
        return GlTextureUnit.values()[OpenGlHelper.lightmapTexUnit - GL_TEXTURE0];
    }
}
