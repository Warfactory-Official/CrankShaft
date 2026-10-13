package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.iris.experimental.mixin.ExperimentalCompositePassMixin;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sundial material banks, evolving background and borrowed opaque depth.
 */
final class ExperimentalSundialReplay implements ExperimentalReplayResources {
    private final RenderTargets targets;
    private final Object last;
    private final Set<Integer> readAlt;
    private final ExperimentalReplayProgram material;
    private final ExperimentalReplayProgram copy;
    private final ExperimentalReplayProgram depth;
    private final ExperimentalReplayProgram depthCopy;
    private final GpuTextureView[] saved = new GpuTextureView[8];
    private final GpuTextureView[] inputs = new GpuTextureView[8];
    private List<GpuTextureView> copyColors = List.of();
    private List<GpuTextureView> materialColors = List.of();
    private List<GpuTextureView> advanceColors = List.of();
    private Map<String, GpuTextureView> copyTextures;
    private Map<String, GpuTextureView> materialTextures;
    private Map<String, GpuTextureView> advanceTextures;
    private GpuTextureView plannedDepth;
    private GpuTextureView plannedResolved;
    private GpuTextureView color;
    private GpuTextureView resolved;
    private GpuTextureView currentDepth;
    private GpuTextureView opaqueDepth;
    private boolean borrowed;

    ExperimentalSundialReplay(IrisRenderingPipeline parent, RenderTargets targets,
                              Object first, Object last) {
        this.targets = targets;
        this.last = last;
        readAlt = ((ExperimentalCompositePassMixin) first).flywheel$readAlt();
        material = new ExperimentalReplayProgram(parent, "native_layer_materialize.frag", false, true,
                ShaderAssembly.NO_EXTRA, Set.of());
        copy = new ExperimentalReplayProgram(parent, "layer_copy.frag", false, false, ShaderAssembly.NO_EXTRA,
                Set.of());
        depth = new ExperimentalReplayProgram(parent, "layer_depth.frag", false, true, ShaderAssembly.NO_EXTRA,
                Set.of());
        depthCopy = new ExperimentalReplayProgram(parent, "native_layer_depth_copy.frag", false, true,
                ShaderAssembly.NO_EXTRA, Set.of());
    }

    private GpuTextureView color(int target) {
        return targets.get(target).getTextureView(readAlt.contains(target), false);
    }

    @Override
    public void prepareCapture() {
        // The pack's preceding composite/composite.csh has already sorted both captured linked lists.
    }

    @Override
    public void snapshot(GpuBuffer indices, GpuBufferSlice command) {
        currentDepth = targets.getDepthTextureView(0);
        opaqueDepth = targets.getDepthTextureView(1);
        color = color(5);
        var plan = ((ExperimentalCompositePassMixin) last).flywheel$attachments();
        int slot = -1;
        for (int i = 0; i < plan.targets().size(); i++) if (plan.targets().get(i).index() == 5) slot = i;
        if (slot < 0) throw new IllegalStateException("Sundial replay exit no longer writes color 5");
        resolved = ((ExperimentalCompositePassMixin) last).flywheel$framebuffer().getColorViews().get(slot);
        inputs[0] = color(0);
        inputs[1] = color(1);
        inputs[2] = color(2);
        inputs[3] = currentDepth;
        inputs[4] = color;
        inputs[5] = opaqueDepth;
        inputs[6] = color;
        inputs[7] = color(4);
        var nodes = ExperimentalReplayState.layerNodes();
        boolean changed = materialColors.isEmpty() || materialColors.get(0) != inputs[0]
                || materialColors.get(1) != inputs[1] || materialColors.get(2) != inputs[2]
                || materialColors.get(3) != color || materialColors.get(4) != nodes
                || copyColors.get(1) != inputs[7] || plannedDepth != currentDepth || plannedResolved != resolved;
        for (int i = 0; i < saved.length; i++) {
            // layer_copy.frag overwrites saved[4] across the whole target before materialize reads it.
            // Empty indirect layers execute neither draw; layer zero always executes both.
            var next = i == 4 ? ExperimentalReplayTextures.ensure(saved[i], inputs[i])
                    : ExperimentalReplayTextures.snapshot(saved[i], inputs[i], depthCopy, indices, command);
            changed |= next != saved[i];
            saved[i] = next;
        }
        // Iris's opaque-depth image has no attachment usage; replay evolves an owned sampled depth instead.
        opaqueDepth = saved[5];
        ExperimentalReplayState.opaqueDepth = opaqueDepth;
        if (changed) {
            copyColors = List.of(saved[4], inputs[7]);
            copyTextures = Map.of("color", color, "weather", saved[7]);
            materialColors = List.of(inputs[0], inputs[1], inputs[2], color, nodes);
            materialTextures = Map.of("saved0", saved[0], "saved1", saved[1], "saved2", saved[2], "savedDepth",
                    saved[3],
                    "saved5", saved[4], "savedCloud", saved[6], "backDepth", opaqueDepth);
            advanceColors = resolved == color ? List.of() : List.of(color);
            advanceTextures = Map.of("depth", currentDepth, "color", resolved == color ? saved[4] : resolved);
            plannedDepth = currentDepth;
            plannedResolved = resolved;
        }
        borrowed = true;
    }

    @Override
    public void materialize(int layer, GpuBuffer indices, GpuBufferSlice command) {
        copy.draw(copyColors, null, copyTextures, indices, command, layer);
        material.draw(materialColors, currentDepth, materialTextures, indices, command, layer);
    }

    @Override
    public void advance(int layer, GpuBuffer indices, GpuBufferSlice command) {
        depth.draw(advanceColors, opaqueDepth, advanceTextures, indices, command, layer);
    }

    @Override
    public void restore() {
        if (borrowed) ExperimentalReplayState.opaqueDepth = null;
        borrowed = false;
    }

    @Override
    public void close() {
        restore();
        for (var view : saved) if (view != null) ExperimentalReplayTextures.retire(view);
        material.close();
        copy.close();
        depth.close();
        depthCopy.close();
    }
}
