package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.iris.mixin.BlendModeOverrideAccessor;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.ShaderDataType;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.option.OrderBackedProperties;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * {@code colorwheel.properties}.
 */
public final class ContractProperties {
    public static final ContractProperties EMPTY = new ContractProperties();

    private final Map<ContractProgram, BlendModeOverride> blend = new EnumMap<>(ContractProgram.class);
    private final Map<ContractProgram, List<BufferBlendInformation>> bufferBlend = new EnumMap<>(ContractProgram.class);
    private final Oit gbuffersOit = new Oit();
    private final Oit shadowOit = new Oit();
    private boolean shadowEnabled = true;

    private ContractProperties() {
    }

    public static @Nullable ContractProperties nativeForwardOit(ProgramSource source, boolean certifiedAlpha) {
        if (!source.isValid() || source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent()
                || source.getTessEvalSource().isPresent() || source.getDirectives().hasUnknownDrawBuffers())
            return null;
        String stages = source.getVertexSource().orElseThrow() + source.getFragmentSource().orElseThrow();
        if (Pattern.compile("\\b(buffer|imageStore|imageAtomic\\w*|atomicCounter\\w*|atomic\\w*|gl_FragDepth)\\b")
                   .matcher(stages).find()) {
            FlwBackend.LOGGER.info(
                    "Shared OIT rejected: {} has depth or storage side effects; retaining native translucency",
                    source.getName());
            return null;
        }
        int[] buffers = source.getDirectives().getDrawBuffers();
        if (buffers.length == 0 || buffers.length > 8) return null;
        BlendModeOverride override = source.getDirectives().getBlendModeOverride().orElse(null);
        BlendMode global = override == null ? new BlendMode(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                GL11C.GL_ONE,
                GL11C.GL_ONE_MINUS_SRC_ALPHA) : ((BlendModeOverrideAccessor) override).flywheel$blendMode();
        ContractProperties result = new ContractProperties();
        result.gbuffersOit.ranks = new int[buffers.length];
        Arrays.fill(result.gbuffersOit.ranks, buffers.length <= 2 ? 3 : buffers.length <= 4 ? 2 : 1);
        for (int slot = 0; slot < buffers.length; slot++) {
            InternalTextureFormat format = source.getParent().getPackDirectives().getRenderTargetDirectives()
                                                 .getRenderTargetSettings().get(buffers[slot]).getInternalFormat();
            if (format.getShaderDataType() != ShaderDataType.FLOAT || format.name().endsWith("_SNORM")) {
                FlwBackend.LOGGER.info("Shared OIT rejected: {} colortex{} has unsupported format {}", source.getName(),
                        buffers[slot], format);
                return null;
            }
            boolean normalized = !format.name().contains("F") && format != InternalTextureFormat.RGB9_E5;
            if (!normalized && !certifiedAlpha) {
                FlwBackend.LOGGER.info(
                        "Shared OIT rejected: {} colortex{} needs a checked floating-point opacity contract",
                        source.getName(), buffers[slot]);
                return null;
            }
            BlendMode mode = global;
            for (BufferBlendInformation buffer : source.getDirectives().getBufferBlendOverrides()) {
                if (buffer.index() == buffers[slot]) mode = buffer.blendMode();
            }
            // Only a declared source-over signal is composable without knowing the pack's deferred material ABI.
            if (mode == null || mode.dstRgb() != GL11C.GL_ONE_MINUS_SRC_ALPHA
                    || mode.srcRgb() != GL11C.GL_SRC_ALPHA && mode.srcRgb() != GL11C.GL_ONE
                    || !sourceOverAlpha(mode)) {
                FlwBackend.LOGGER.info(
                        "Shared OIT rejected: {} colortex{} is not source-over; retaining native translucency",
                        source.getName(), buffers[slot]);
                return null;
            }
            result.gbuffersOit.accumulate.put(buffers[slot], new Accumulate(slot, InternalTextureFormat.RGBA32F,
                    mode.srcRgb() == GL11C.GL_ONE, normalized));
        }
        result.gbuffersOit.enabled = true;
        result.blend.put(ContractProgram.GBUFFERS_TRANSLUCENT,
                override == null ? new BlendModeOverride(global) : override);
        result.bufferBlend.put(ContractProgram.GBUFFERS_TRANSLUCENT, source.getDirectives().getBufferBlendOverrides());
        FlwBackend.LOGGER.info("Shared forward OIT accepted: {} targets {} ranks {}", source.getName(),
                Arrays.toString(buffers), Arrays.toString(result.gbuffersOit.ranks));
        return result;
    }

    private static boolean sourceOverAlpha(BlendMode mode) {
        return mode.srcAlpha() == GL11C.GL_ONE && mode.dstAlpha() == GL11C.GL_ONE_MINUS_SRC_ALPHA
                || mode.srcAlpha() == GL11C.GL_ONE_MINUS_DST_ALPHA && mode.dstAlpha() == GL11C.GL_ONE
                || mode.srcAlpha() == GL11C.GL_ZERO && mode.dstAlpha() == GL11C.GL_ONE;
    }

    public static ContractProperties parse(String source, ShaderPackOptions options, Iterable<StringPair> defines) {
        Properties properties = new OrderBackedProperties();
        try {
            properties.load(new StringReader(PropertiesPreprocessor.preprocessSource(source, options, defines)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ContractProperties parsed = new ContractProperties();
        properties.forEach((k, v) -> parsed.accept(((String) k).trim(), ((String) v).trim()));
        return parsed;
    }

    private static BlendMode blendMode(String value) {
        String[] functions = value.split("\\s+");
        if (functions.length != 4) {
            throw new IllegalStateException("Blend mode needs 4 functions: " + value);
        }
        int[] ids = new int[4];
        for (int i = 0; i < 4; i++) {
            String function = functions[i];
            ids[i] = BlendModeFunction.fromString(function)
                                      .orElseThrow(
                                              () -> new IllegalStateException("Unknown blend function " + function))
                                      .getGlId();
        }
        return new BlendMode(ids[0], ids[1], ids[2], ids[3]);
    }

    private void accept(String key, String value) {
        if (key.equals("shadow.enabled")) {
            shadowEnabled = Boolean.parseBoolean(value);
        } else if (key.equals("oit")) {
            gbuffersOit.enabled = shadowOit.enabled = Boolean.parseBoolean(value);
            gbuffersOit.declared = shadowOit.declared = true;
        } else if (key.startsWith("oit.")) {
            String[] path = key.substring("oit.".length())
                               .split("\\.");
            boolean shadow = path[0].equals("shadow");
            if (!shadow && !path[0].equals("gbuffers")) {
                throw new IllegalStateException("colorwheel.properties " + key + ": unknown OIT group");
            }
            (shadow ? shadowOit : gbuffersOit).accept(key, path, value, shadow ? "shadowcolor" : "colortex");
        } else if (key.startsWith("blend.")) {
            String[] path = key.substring("blend.".length())
                               .split("\\.");
            ContractProgram program = ContractProgram.byName(path[0]);
            if (program == null || path.length > 2) {
                FlwBackend.LOGGER.warn("Unknown colorwheel.properties key {}", key);
                return;
            }
            BlendMode mode = value.equals("off") ? null : blendMode(value);
            if (path.length == 1) {
                blend.put(program, mode == null ? BlendModeOverride.OFF : new BlendModeOverride(mode));
                return;
            }
            if (!IrisRenderSystem.supportsBufferBlending()) {
                throw new IllegalStateException("colorwheel.properties " + key + " needs buffer blending");
            }
            String prefix = program.shadow ? "shadowcolor" : "colortex";
            if (!path[1].startsWith(prefix)) {
                throw new IllegalStateException("colorwheel.properties " + key + ": expected " + prefix + "N");
            }
            bufferBlend.computeIfAbsent(program, p -> new ArrayList<>())
                       .add(new BufferBlendInformation(Integer.parseInt(path[1].substring(prefix.length())), mode));
        }
    }

    public boolean shadowEnabled() {
        return shadowEnabled;
    }

    /**
     * {@code null}: none set; shadow programs then default to {@link BlendModeOverride#OFF}.
     */
    public @Nullable BlendModeOverride blend(ContractProgram program) {
        return blend.get(program);
    }

    public List<BufferBlendInformation> bufferBlend(ContractProgram program) {
        return bufferBlend.getOrDefault(program, List.of());
    }

    public Oit oit(boolean shadow) {
        return shadow ? shadowOit : gbuffersOit;
    }

    /**
     * {@code coefficient}: index into {@link Oit#ranks()}, or {@link #FRONTMOST}.
     */
    public record Accumulate(int coefficient, InternalTextureFormat format, boolean premultiplied, boolean clampInput) {
        public static final int FRONTMOST = -1;

        public Accumulate(int coefficient, InternalTextureFormat format) {
            this(coefficient, format, false, false);
        }
    }

    /**
     * Wavelet OIT for one program group: {@code ranks} per coefficient set (1..3); accumulate per draw buffer.
     */
    public static final class Oit {
        private static final Accumulate DEFAULT = new Accumulate(Accumulate.FRONTMOST, InternalTextureFormat.RGBA16F);
        private final Map<Integer, Accumulate> accumulate = new HashMap<>();
        private boolean enabled;
        private boolean declared;
        private int[] ranks = new int[0];

        public boolean enabled() {
            return enabled;
        }

        boolean explicitlyDisabled() {
            return declared && !enabled;
        }

        public int[] ranks() {
            return ranks;
        }

        public Accumulate accumulate(int drawBuffer) {
            return accumulate.getOrDefault(drawBuffer, DEFAULT);
        }

        // Colorwheel defaults: an entry created by its coefficient line is RGBA8, by its format line frontmost.
        private void accept(String key, String[] path, String value, String bufferPrefix) {
            if (path.length == 1) {
                enabled = Boolean.parseBoolean(value);
                declared = true;
            } else if (path.length == 2 && path[1].equals("coefficientRanks")) {
                ranks = Arrays.stream(value.split(","))
                              .mapToInt(rank -> Integer.parseInt(rank.trim()))
                              .toArray();
                if (Arrays.stream(ranks)
                          .anyMatch(rank -> rank < 1 || rank > 3)) {
                    throw new IllegalStateException("colorwheel.properties " + key + ": ranks must be 1..3");
                }
            } else if (path[1].startsWith(bufferPrefix) && path.length <= 3) {
                int drawBuffer = Integer.parseInt(path[1].substring(bufferPrefix.length()));
                Accumulate current = accumulate.get(drawBuffer);
                if (path.length == 2) {
                    int coefficient = value.equals("frontmost") ? Accumulate.FRONTMOST : Integer.parseInt(value);
                    accumulate.put(drawBuffer, new Accumulate(coefficient,
                            current == null ? InternalTextureFormat.RGBA8 : current.format()));
                } else if (path[2].equals("format")) {
                    accumulate.put(drawBuffer,
                            new Accumulate(current == null ? Accumulate.FRONTMOST : current.coefficient(),
                                    InternalTextureFormat.valueOf(value)));
                } else {
                    throw new IllegalStateException("Unknown colorwheel.properties key " + key);
                }
            } else {
                throw new IllegalStateException("Unknown colorwheel.properties key " + key);
            }
        }
    }
}
