package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.iris.compile.patches.SeusReplayShaders;
import dev.engine_room.flywheel.iris.experimental.mixin.ExperimentalCompositePassMixin;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SEUS replay preserves both material/lighting banks, reflection history and optional HRR 3 motion.
 */
final class ExperimentalSeusReplay implements ExperimentalReplayResources {
    private final RenderTargets targets;
    private final Object last;
    private final Set<Integer> readAlt;
    private final boolean motion;
    private final boolean restoreColor0;
    private final ExperimentalReplayProgram sort;
    private final ExperimentalReplayProgram material;
    private final ExperimentalReplayProgram restore;
    private final ExperimentalReplayProgram advance;
    private final ExperimentalReplayProgram depthCopy;
    private final GpuTextureView[] saved = new GpuTextureView[7];
    private final GpuTextureView[] sources = new GpuTextureView[7];
    private final Vector3i sortGroups = new Vector3i();
    private List<GpuTextureView> restoreColors = List.of();
    private List<GpuTextureView> materialColors = List.of();
    private List<GpuTextureView> advanceColors;
    private Map<String, GpuTextureView> sortTextures = Map.of();
    private Map<String, GpuTextureView> restoreTextures;
    private Map<String, GpuTextureView> materialTextures;
    private Map<String, GpuTextureView> advanceTextures;
    private GpuTextureView sortDepth;
    private GpuTextureView plannedDepth;
    private GpuTextureView plannedResolved;
    private GpuTextureView savedDepth;
    private GpuTextureView savedOpaque;
    private GpuTextureView workingDepth;
    private GpuTextureView backColor;

    ExperimentalSeusReplay(IrisRenderingPipeline parent, RenderTargets targets, List<Object> stages,
                           boolean motion) {
        this.targets = targets;
        this.motion = motion;
        last = stages.getLast();
        readAlt = ((ExperimentalCompositePassMixin) stages.getFirst()).flywheel$readAlt();
        restoreColor0 = stages.stream().anyMatch(stage -> {
            for (int target : ((ExperimentalCompositePassMixin) stage).flywheel$drawBuffers())
                if (target == 0) return true;
            for (var compute : ((ExperimentalCompositePassMixin) stage).flywheel$computes())
                if (compute != null) return true;
            return false;
        });
        sort = new ExperimentalReplayProgram(parent, "seus_sort.comp", true, false, SeusReplayShaders::sort, Set.of());
        material = new ExperimentalReplayProgram(parent, "seus_materialize.frag", false, true, compilation -> {
            if (motion) compilation.define("FLW_SEUS_MOTION");
            else {
                compilation.define("FLW_SEUS_NODE_LOCATION", "2");
                compilation.define("FLW_SEUS_MIRROR_LOCATION", "3");
            }
        }, Set.of());
        restore = new ExperimentalReplayProgram(parent, "native_seus_restore.frag", false, false, compilation -> {
            if (restoreColor0) compilation.define("FLW_SEUS_RESTORE_COLOR0");
        }, Set.of());
        advance = new ExperimentalReplayProgram(parent, "layer_depth.frag", false, true, ShaderAssembly.NO_EXTRA,
                Set.of());
        depthCopy = new ExperimentalReplayProgram(parent, "native_layer_depth_copy.frag", false, true,
                ShaderAssembly.NO_EXTRA, Set.of());
    }

    private GpuTextureView color(int index) {
        return targets.get(index).getTextureView(readAlt.contains(index), false);
    }

    private GpuTextureView bank(int index, boolean alt) {
        return targets.get(index).getTextureView(alt, false);
    }

    @Override
    public void prepareCapture() {
        int width = targets.getCurrentWidth(), height = targets.getCurrentHeight();
        var opaque = targets.getDepthTextureView(1);
        if (sortDepth != opaque) {
            sortDepth = opaque;
            sortTextures = Map.of("opaqueDepth", opaque);
        }
        sort.dispatch(sortTextures, width, height, sortGroups.set((width / 2 + 8) / 8, (height / 2 + 8) / 8, 1));
    }

    @Override
    public void snapshot(GpuBuffer indices, GpuBufferSlice command) {
        boolean changed = materialColors.isEmpty();
        changed |= source(0, restoreColor0 ? bank(0, false) : null);
        changed |= source(1, restoreColor0 ? bank(0, true) : null);
        changed |= source(2, color(1));
        changed |= source(3, color(2));
        changed |= source(4, bank(7, false));
        changed |= source(5, bank(7, true));
        changed |= source(6, motion ? color(8) : null);
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] == null) continue;
            var next = ExperimentalReplayTextures.snapshot(saved[i], sources[i], depthCopy, indices, command);
            changed |= saved[i] != next;
            saved[i] = next;
        }
        var currentDepth = targets.getDepthTextureView(0);
        var opaque = targets.getDepthTextureView(1);
        var nextDepth = ExperimentalReplayTextures.snapshot(savedDepth, currentDepth, depthCopy, indices, command);
        var nextOpaque = ExperimentalReplayTextures.snapshot(savedOpaque, opaque, depthCopy, indices, command);
        var nextWorking = ExperimentalReplayTextures.snapshot(workingDepth, opaque, depthCopy, indices, command);
        var nextBack = ExperimentalReplayTextures.snapshot(backColor, sources[2], depthCopy, indices, command);
        changed |= savedDepth != nextDepth || savedOpaque != nextOpaque || workingDepth != nextWorking || backColor != nextBack;
        savedDepth = nextDepth;
        savedOpaque = nextOpaque;
        workingDepth = nextWorking;
        backColor = nextBack;
        var nodes = ExperimentalReplayState.layerNodes();
        var mirror = bank(1, !readAlt.contains(1));
        var plan = ((ExperimentalCompositePassMixin) last).flywheel$attachments();
        int slot = -1;
        for (int i = 0; i < plan.targets().size(); i++) if (plan.targets().get(i).index() == 1) slot = i;
        if (slot < 0) throw new IllegalStateException("SEUS replay exit no longer writes material color");
        var resolved = ((ExperimentalCompositePassMixin) last).flywheel$framebuffer().getColorViews().get(slot);
        changed |= plannedDepth != currentDepth || plannedResolved != resolved
                || !materialColors.isEmpty() && (materialColors.get(materialColors.size() - 2) != nodes
                || materialColors.getLast() != mirror);
        // Contents are copied every frame; immutable draw plans only depend on view identities.
        if (changed) plan(currentDepth, resolved, nodes, mirror);
        ExperimentalReplayState.opaqueDepth = workingDepth;
    }

    private boolean source(int index, GpuTextureView view) {
        boolean changed = sources[index] != view;
        sources[index] = view;
        return changed;
    }

    private void plan(GpuTextureView currentDepth, GpuTextureView resolved, GpuTextureView nodes,
                      GpuTextureView mirror) {
        var restoreTargets = new ArrayList<GpuTextureView>();
        var restored = new HashMap<String, GpuTextureView>();
        if (restoreColor0) {
            restoreTargets.add(sources[0]);
            restoreTargets.add(sources[1]);
            restored.put("saved0Main", saved[0]);
            restored.put("saved0Alt", saved[1]);
        }
        restoreTargets.add(sources[4]);
        restoreTargets.add(sources[5]);
        restored.put("saved7Main", saved[4]);
        restored.put("saved7Alt", saved[5]);
        restoreColors = List.copyOf(restoreTargets);
        restoreTextures = Map.copyOf(restored);
        var colors = new ArrayList<>(List.of(sources[2], sources[3]));
        var textures = new HashMap<>(Map.of("saved1", saved[2], "saved2", saved[3], "savedDepth", savedDepth,
                "backColor", backColor, "backDepth", workingDepth, "savedOpaque", savedOpaque));
        if (motion) {
            colors.add(sources[6]);
            textures.put("savedMotion", saved[6]);
        }
        colors.add(nodes);
        colors.add(mirror);
        materialColors = List.copyOf(colors);
        materialTextures = Map.copyOf(textures);
        advanceColors = List.of(backColor);
        advanceTextures = Map.of("depth", currentDepth, "color", resolved);
        plannedDepth = currentDepth;
        plannedResolved = resolved;
    }

    @Override
    public void materialize(int layer, GpuBuffer indices, GpuBufferSlice command) {
        restore.draw(restoreColors, null, restoreTextures, indices, command, layer);
        material.draw(materialColors, plannedDepth, materialTextures, indices, command, layer);
    }

    @Override
    public void advance(int layer, GpuBuffer indices, GpuBufferSlice command) {
        advance.draw(advanceColors, workingDepth, advanceTextures, indices, command, layer);
    }

    @Override
    public void restore() {
        ExperimentalReplayState.opaqueDepth = null;
    }

    @Override
    public void close() {
        restore();
        for (var view : saved) if (view != null) ExperimentalReplayTextures.retire(view);
        for (var view : new GpuTextureView[]{savedDepth, savedOpaque, workingDepth, backColor}) {
            if (view != null) ExperimentalReplayTextures.retire(view);
        }
        sort.close();
        material.close();
        restore.close();
        advance.close();
        depthCopy.close();
    }
}
