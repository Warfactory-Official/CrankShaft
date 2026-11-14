package dev.engine_room.flywheel.api.instance;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The programs and storage sizes of a GPU population. Shader paths have the same implicit
 * {@code flywheel/} prefix as {@link InstanceType#vertexShader()}.
 *
 * <p>All three programs share these storage bindings:</p>
 * <ol start="0">
 *     <li>Persistent simulation state, with {@code stateStride} bytes per population slot.</li>
 *     <li>Persistent control data, with {@code controlSize} bytes. Its first four uints are the engine's
 *     {@code count}, {@code tick}, {@code sortNeeded}, and {@code capacity}; application fields begin at byte 16.</li>
 *     <li>The {@code parameterSize}-byte parameter block supplied through {@link GpuPopulation#parameters}.</li>
 *     <li>The engine's output instance buffer, using the population's {@link InstanceType#layout()}.</li>
 *     <li>An address table containing {@code capacity} uint offsets into the output instance buffer,
 *     followed by {@code ceil(capacity / 32)} page slots. Object offsets are measured in uints.</li>
 *     <li>Sort keys, one {@code uvec4(keyLow, keyHigh, id, valid)} per population slot.</li>
 *     <li>Persistent sorted population slot IDs.</li>
 *     <li>The {@code auxiliarySize}-byte auxiliary block supplied through {@link GpuPopulation#auxiliary}.</li>
 * </ol>
 *
 * <p>The initialize program runs with one work group. Each simulation tick runs the tick program with
 * {@code flw_phase = 0} for preparation in one group, {@code flw_phase = 1} for updating slots in
 * {@code ceil(capacity / workGroupSize)} groups, and {@code flw_phase = 2} for finishing in one group.
 * At most {@code maxTicksPerFrame} ticks run during one frame preparation.</p>
 *
 * <p>The frame program runs over all population slots with {@code flw_phase = 0} to produce depth keys.
 * After stable 64-bit key sorting, it runs over all slots with {@code flw_phase = 1} to emit output instances
 * in the retained order. Both phases use {@code ceil(capacity / workGroupSize)} groups.</p>
 *
 * <p>Each frame key is {@code uvec4(keyLow, keyHigh, id, valid)}. Sorting compares the unsigned high and low words
 * in ascending order and retains the prior order of equal keys. The emit phase may update {@code valid}; only
 * records with a nonzero value participate in rendering.</p>
 *
 * @param doublePrecision whether the programs require double-precision floating-point support
 * @param defines preprocessor flags applied before assembling any shader dependencies
 * @param viewDepthSorted whether emitted records are camera-facing billboards in far-to-near interpolated camera
 *                        view-depth order; equal depths retain their previous order
 */
public record GpuSimulation(Identifier initializeShader, Identifier tickShader, Identifier frameShader,
                            int stateStride, int controlSize, int parameterSize, int auxiliarySize,
                            int workGroupSize, int maxTicksPerFrame, boolean doublePrecision, List<String> defines,
                            boolean viewDepthSorted) {
    public GpuSimulation {
        defines = List.copyOf(defines);
    }

    public GpuSimulation(Identifier initializeShader, Identifier tickShader, Identifier frameShader,
                         int stateStride, int controlSize, int parameterSize, int auxiliarySize,
                         int workGroupSize, int maxTicksPerFrame, boolean doublePrecision, List<String> defines) {
        this(initializeShader, tickShader, frameShader, stateStride, controlSize, parameterSize, auxiliarySize,
                workGroupSize, maxTicksPerFrame, doublePrecision, defines, false);
    }

    public GpuSimulation(Identifier initializeShader, Identifier tickShader, Identifier frameShader,
                         int stateStride, int controlSize, int parameterSize, int auxiliarySize,
                         int workGroupSize, int maxTicksPerFrame, boolean doublePrecision) {
        this(initializeShader, tickShader, frameShader, stateStride, controlSize, parameterSize, auxiliarySize,
                workGroupSize, maxTicksPerFrame, doublePrecision, List.of(), false);
    }
}
