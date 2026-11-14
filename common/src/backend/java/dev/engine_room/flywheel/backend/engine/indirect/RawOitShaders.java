package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;

/**
 * Shared native shading outputs for raw host draws and reconstructed layers. Assembled through Compilation.
 */
public final class RawOitShaders {
    public static final int DEPTH_RANGE_UNIT = 17;
    public static final int COEFFICIENT_UNIT = 18;
    public static final int NOISE_UNIT = 22;
    public static final int ACCUMULATE_UNIT = 23;

    private RawOitShaders() {
    }

    /**
     * Appends shared equations and multi-hit outputs. A host's location-0 output may supply _flw_rawOut0.
     */
    public static void appendProducer(Compilation compilation, @Nullable OitMode wavelet,
                                      @Nullable OitInsertMode insert, boolean externalFirstOutput) {
        compilation.define("_FLW_OIT_OUTPUTS_EXTERNAL");
        if (insert != null) RenderPassShaders.mlabProducerDefines(compilation, insert);
        else compilation.define(wavelet.define);
        compilation.define("_flw_rawLock()", insert == OitInsertMode.MLAB ? "beginInvocationInterlockARB()" : "");
        compilation.define("_flw_rawUnlock()", insert == OitInsertMode.MLAB ? "endInvocationInterlockARB()" : "");
        compilation.appendComponent(new RawSource("raw OIT output declarations", """
                vec4 _flw_depthRange_out;
                vec4 _flw_coeffs0, _flw_coeffs1, _flw_coeffs2, _flw_coeffs3;
                vec4 _flw_accumulate;
                """ + (externalFirstOutput ? "" : "layout(location=0) out vec4 _flw_rawOut0;\n") + """
                #ifdef _FLW_COLLECT_COEFFS
                layout(location=1) out vec4 _flw_rawOut1;
                layout(location=2) out vec4 _flw_rawOut2;
                layout(location=3) out vec4 _flw_rawOut3;
                #endif
                """));
        if (insert != null) append(compilation, "internal/mlab.glsl");
        else append(compilation, "internal/wavelet.glsl", "internal/oit_producer.glsl");
        compilation.appendComponent(new RawSource("raw OIT view and emission", """
                uniform mat4 _flw_rawInverseProjection;
                uniform vec2 _flw_rawSize;
                uniform bool _flw_rawZeroToOne;
                uniform bool _flw_rawReverseZ;
                float _flw_rawDeviceDepth(float depth) {
                    return _flw_rawReverseZ ? depth : 1.0 - depth;
                }
                float _flw_rawLinearDepth(float depth) {
                    vec4 view = _flw_rawInverseProjection * vec4(
                            gl_FragCoord.xy / _flw_rawSize * 2.0 - 1.0,
                            _flw_rawZeroToOne ? depth : depth * 2.0 - 1.0, 1.0);
                    return -view.z / view.w;
                }
                void _flw_rawBegin() {
                    #ifdef _FLW_DEPTH_RANGE
                    _flw_rawOut0 = vec4(-3.402823e38, -3.402823e38, 0.0, 0.0);
                    #else
                    _flw_rawOut0 = vec4(0.0);
                    #endif
                    #ifdef _FLW_COLLECT_COEFFS
                    _flw_rawOut1 = _flw_rawOut2 = _flw_rawOut3 = vec4(0.0);
                    #endif
                }
                void _flw_rawEmit(vec4 color, float linearDepth, float reverseDeviceDepth) {
                    #ifdef _FLW_OIT_MLAB
                    uvec2 sampleNode = uvec2(floatBitsToUint(reverseDeviceDepth),
                            packUnorm4x8(vec4(color.rgb * color.a, color.a)));
                    if (sampleNode.y != 0u) _flw_mlabInsertPrepared(_flw_mlabPixelIndex(), sampleNode);
                    #elif defined(_FLW_OIT_INSERT)
                    _flw_mlabInsert(color, reverseDeviceDepth);
                    #elif defined(_FLW_DEPTH_RANGE)
                    _flw_writeDepthRange(linearDepth, reverseDeviceDepth);
                    _flw_rawOut0 = max(_flw_rawOut0, _flw_depthRange_out);
                    #else
                    _flw_oitEmit(color, linearDepth);
                    #ifdef _FLW_COLLECT_COEFFS
                    _flw_rawOut0 += _flw_coeffs0;
                    _flw_rawOut1 += _flw_coeffs1;
                    _flw_rawOut2 += _flw_coeffs2;
                    _flw_rawOut3 += _flw_coeffs3;
                    #else
                    _flw_rawOut0 += _flw_accumulate;
                    #endif
                    #endif
                }
                """));
    }

    public static void append(Compilation compilation, String... paths) {
        ShaderAssembly.appendComponents(compilation,
                Arrays.stream(paths).<SourceComponent>map(path -> FlwPrograms.SOURCES.get(ResourceUtil.rl(path)))
                      .toList());
    }

    /**
     * Bind after the program; render thread, once per view or material program.
     */
    public static void bind(GlProgram program, RawOitTarget target) {
        try (var stack = MemoryStack.stackPush()) {
            GL20C.glUniformMatrix4fv(program.getUniformLocation("_flw_rawInverseProjection"), false,
                    new Matrix4f(target.projection()).invert().get(stack.mallocFloat(16)));
            GL20C.glUniform2f(program.getUniformLocation("_flw_rawSize"), target.width(), target.height());
            GL20C.glUniform1i(program.getUniformLocation("_flw_rawZeroToOne"), target.zeroToOne() ? 1 : 0);
            GL20C.glUniform1i(program.getUniformLocation("_flw_rawReverseZ"), target.reverseZ() ? 1 : 0);
        }
        samplers(program);
    }

    public static void samplers(GlProgram program) {
        GL20C.glUniform1i(program.getUniformLocation("_flw_depthRange"), DEPTH_RANGE_UNIT);
        GL20C.glUniform1i(program.getUniformLocation("_flw_blueNoise"), NOISE_UNIT);
        GL20C.glUniform1i(program.getUniformLocation("_flw_accumulate"), ACCUMULATE_UNIT);
        GL20C.glUniform1i(program.getUniformLocation("_flw_mlabNearest"), ACCUMULATE_UNIT);
        for (int i = 0; i < 4; i++)
            GL20C.glUniform1i(program.getUniformLocation("_flw_coefficients" + i), COEFFICIENT_UNIT + i);
        for (String sampler : OitPipelines.MLAB_LAYER_SAMPLERS)
            GL20C.glUniform1i(program.getUniformLocation(sampler), DEPTH_RANGE_UNIT);
    }
}
