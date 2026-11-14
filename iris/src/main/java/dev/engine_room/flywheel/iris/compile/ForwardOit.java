package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.parsing.ConstDirectiveParser;
import net.irisshaders.iris.shaderpack.parsing.DispatchingDirectiveHolder;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dimension-owned forward wavelet material variants. Sources are built at pack load; the render thread marks
 * gathered geometry pending and consumes it once at the pack's lighting boundary. Reload destroys that owner.
 */
public final class ForwardOit {
    public static final String PREFIX = "crankshaft_forward_";
    public static final String END = "<end>";
    public final DeferredOitProfile profile;
    public final ContractProperties properties;
    public final InternalTextureFormat format;
    private final int[] waterBuffers;
    private final BlendModeOverride waterBlend;
    private final List<BufferBlendInformation> waterBufferBlends;
    private final Map<String, ProgramSource> sources = new HashMap<>();
    private boolean pending;
    private String beforeProgram;

    public ForwardOit(DeferredOitProfile profile, ProgramSet programs) {
        this.profile = profile;
        var water = programs.get(ProgramId.Water).orElseThrow().getDirectives();
        waterBuffers = water.getDrawBuffers();
        waterBlend = water.getBlendModeOverride().orElseGet(() -> new BlendModeOverride(
                new BlendMode(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                        GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA)));
        waterBufferBlends = water.getBufferBlendOverrides();
        var directives = new DispatchingDirectiveHolder();
        programs.getPackDirectives().getRenderTargetDirectives().acceptDirectives(directives);
        ConstDirectiveParser.findDirectives(
                                    "const int colortex15Format = RGBA32F;\nconst bool colortex15Clear = false;")
                            .forEach(directives::processDirective);
        format = programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings().get(target())
                         .getInternalFormat();
        properties = ContractProperties.forward(this);
    }

    public static @Nullable ForwardOit get(IrisRenderingPipeline pipeline) {
        return ((ProgramFallbackResolverAccessor) ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver())
                .flywheel$programs().flywheel$forwardOit();
    }

    public int target() {
        return switch (profile) {
            case SUNDIAL -> 5;
            // The opaque HDR background is ready before the native translucent stage.
            case ITERATION -> 6;
            case SEUS, SEUS_HRR3, SEUS_GFME -> 1;
        };
    }

    public boolean packed() {
        return profile == DeferredOitProfile.SEUS || profile == DeferredOitProfile.SEUS_HRR3
                || profile == DeferredOitProfile.SEUS_GFME;
    }

    /**
     * Snapshot coordinates match the native packed HDR texels used by the late resolve.
     */
    public SnapshotRect snapshotRect(int width, int height) {
        boolean packed = packed();
        int x = packed ? width / 2 + 1 : 0;
        int y = packed ? height / 2 + 1 : 0;
        return new SnapshotRect(x, y, width - x, height - y);
    }

    /**
     * Packed native geometry retains a jittered edge texel beyond the lower-left half.
     */
    public SnapshotRect captureRect(int width, int height) {
        return new SnapshotRect(0, 0, packed() ? width / 2 + 1 : width,
                packed() ? height / 2 + 1 : height);
    }

    public record SnapshotRect(int x, int y, int width, int height) {
        public boolean empty() {
            return width == 0 || height == 0;
        }
    }

    public int[] drawBuffers() {
        return new int[]{target(), 14};
    }

    public int[] resolveBuffers() {
        return new int[]{target()};
    }

    public int[] waterBuffers() {
        return waterBuffers;
    }

    public BlendModeOverride waterBlend() {
        return waterBlend;
    }

    public List<BufferBlendInformation> waterBufferBlends() {
        return waterBufferBlends;
    }

    public int boundary(List<String> programs) {
        String surface = switch (profile) {
            case SUNDIAL -> "composite1";
            case ITERATION -> "composite50";
            case SEUS, SEUS_HRR3, SEUS_GFME -> "composite";
        };
        for (int i = 0; i < programs.size(); i++) {
            if (!programs.get(i).equals(surface)) continue;
            beforeProgram = surface;
            return i;
        }
        return -1;
    }

    public void source(ProgramSource original, ProgramSource variant) {
        sources.put(original.getName(), variant.withDirectiveOverride(
                variant.getDirectives().withOverriddenDrawBuffers(drawBuffers())));
    }

    public ProgramSource source(ProgramSource original) {
        return sources.get(original.getName());
    }

    public void gathered() {
        pending = true;
    }

    public boolean consume(String program) {
        if (!pending || !program.equals(beforeProgram)) return false;
        pending = false;
        return true;
    }

    public void finish() {
        pending = false;
    }

    public BlendModeOverride blend() {
        return BlendModeOverride.OFF;
    }
}
