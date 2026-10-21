package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.api.pipeline.fbo.Attachment;
import com.google.common.primitives.Ints;
import com.luna.ausm.api.pipeline.pack.ShaderProgramDirectives;
import com.luna.ausm.api.pipeline.shader.ProgramArrayId;
import com.luna.ausm.api.pipeline.shader.RenderPass;
import com.luna.ausm.impl.pipeline.PipelineContext;
import com.luna.ausm.impl.pipeline.shader.ComputeProgram;
import com.luna.ausm.impl.pipeline.shader.FullscreenArrayProgram;
import com.luna.ausm.impl.pipeline.shader.PipelineProgram;

import java.util.*;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntArrays;

final class AusmReplayPasses {
    private AusmReplayPasses() {
    }

    static List<DeferredReplayPass> create(PipelineContext context, ProgramArrayId array, AusmReplayTargets targets) {
        var descriptor = context.fullscreenProgramArrays.get(array);
        var fixed = descriptor == null ? List.<RenderPass>of() : descriptor.fixedPasses();
        var extras = context.fullscreenArrayPrograms.getOrDefault(array, List.of());
        var computes = context.computeProgramArrays.getOrDefault(array, List.of());
        var max = fixed.size() - 1;
        for (var program : extras) max = Math.max(max, program.index());
        for (var compute : computes) max = Math.max(max, compute.arrayIndex());
        var alt = targets.readAltScratch();
        var result = new ArrayList<DeferredReplayPass>();
        for (var index = 0; index <= max; index++) {
            var program = index < fixed.size() ? context.programs.get(fixed.get(index)) : null;
            if (program != null && !program.hasOwnProgram()) program = null;
            FullscreenArrayProgram extra = null;
            for (var candidate : extras) {
                if (candidate.index() != index || !candidate.hasProgram()) continue;
                if (program != null || extra != null)
                    throw new UnsupportedOperationException("Multiple graphics programs in one replay stage");
                extra = candidate;
            }
            var activeComputes = new ArrayList<ComputeProgram>();
            for (var compute : computes) if (compute.arrayIndex() == index) activeComputes.add(compute);
            if (program == null && extra == null && activeComputes.isEmpty()) continue;
            var name = program != null ? program.pass().name().toLowerCase(Locale.ROOT) : extra != null ? extra.name()
                    : array.name().toLowerCase(Locale.ROOT) + (index == 0 ? "" : index);
            var directives = program != null ? program.directives() : extra != null ? extra.directives() : null;
            var outputs = program != null ? program.drawBuffers() : extra != null ? extra.drawBuffers() : List.<Attachment>of();
            var entryAlt = new IntOpenHashSet(alt);
            // AUSM performs this compatibility flip before binding the composite's samplers.
            if (name.equals("composite5") && outputs.stream().anyMatch(output -> output.getIndex() == 2))
                toggle(alt, 2);
            var readAlt = new IntOpenHashSet(alt);
            var pass = new Pass(context, array, index, program, extra, activeComputes, directives, outputs,
                    name, targets, entryAlt, readAlt);
            result.add(pass);
            for (var output : pass.flippedOutputs) toggle(alt, output);
        }
        return result;
    }

    private static void toggle(IntSet values, int index) {
        if (!values.remove(index)) values.add(index);
    }

    static void prepareFrame(List<DeferredReplayPass> passes, AusmReplayTargets targets) {
        var alt = targets.readAltScratch();
        for (var entry : passes) {
            var pass = (Pass) entry;
            pass.entryAlt.clear();
            pass.entryAlt.addAll(alt);
            if (pass.flipBefore)
                toggle(alt, 2);
            pass.readAlt.clear();
            pass.readAlt.addAll(alt);
            for (var output : pass.flippedOutputs) toggle(alt, output);
        }
    }

    private static final class Pass implements DeferredReplayPass {
        private final PipelineContext context;
        private final ProgramArrayId array;
        private final int index;
        private final PipelineProgram fixed;
        private final FullscreenArrayProgram extra;
        private final List<ComputeProgram> nativeComputes;
        private final Compute[] computes;
        private final ShaderProgramDirectives directives;
        private final int[] outputs;
        private final String name;
        private final AusmReplayTargets targets;
        private final IntSet entryAlt, readAlt;
        private final int[] flippedOutputs;
        private final boolean flipBefore;

        Pass(PipelineContext context, ProgramArrayId array, int index, PipelineProgram fixed,
             FullscreenArrayProgram extra,
             List<ComputeProgram> nativeComputes, ShaderProgramDirectives directives, List<Attachment> outputs,
             String name, AusmReplayTargets targets, IntSet entryAlt, IntSet readAlt) {
            this.context = context;
            this.array = array;
            this.index = index;
            this.fixed = fixed;
            this.extra = extra;
            this.nativeComputes = nativeComputes;
            this.directives = directives;
            this.outputs = outputs.stream().mapToInt(Attachment::getIndex).toArray();
            this.name = name;
            this.targets = targets;
            this.entryAlt = entryAlt;
            this.readAlt = readAlt;
            flippedOutputs = directives == null ? IntArrays.EMPTY_ARRAY
                    : Arrays.stream(directives.flippedAttachments(outputs)).mapToInt(Attachment::getIndex).toArray();
            flipBefore = name.equals("composite5") && Ints.contains(this.outputs, 2);
            computes = new Compute[nativeComputes.size()];
            for (var slot = 0; slot < computes.length; slot++)
                computes[slot] = new Dispatch(context, nativeComputes.get(slot), targets);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int program() {
            return fixed != null ? fixed.shaderProgram().getId() : extra != null ? extra.shaderProgram().getId() : 0;
        }

        @Override
        public Compute[] computes() {
            return computes;
        }

        @Override
        public int width() {
            return directives == null ? targets.width() : directives.viewportScale().width(targets.width());
        }

        @Override
        public int height() {
            return directives == null ? targets.height() : directives.viewportScale().height(targets.height());
        }

        @Override
        public int[] drawBuffers() {
            return outputs;
        }

        @Override
        public IntSet readAlt() {
            return readAlt;
        }

        @Override
        public int outputTexture(int slot) {
            var index = outputs[slot];
            var target = targets.get(index);
            return readAlt.contains(index) ? target.main() : target.alt();
        }

        @Override
        public void execute() {
            targets.readAlt(entryAlt);
            context.runComputeProgramsForArrayIndex(nativeComputes, index, context.computeBindingPass(array));
            if (fixed != null) context.runFullscreenPass(fixed);
            if (extra != null) context.runFullscreenArrayProgram(extra);
        }
    }

    private static final class Dispatch implements DeferredReplayPass.Compute {
        private final ComputeProgram source;
        private final PipelineContext context;
        private final AusmReplayTargets targets;
        private int width = -1, height = -1;
        private int[] groups;

        Dispatch(PipelineContext context, ComputeProgram source, AusmReplayTargets targets) {
            this.context = context;
            this.source = source;
            this.targets = targets;
        }

        private int[] groups() {
            if (width != targets.width() || height != targets.height()) {
                width = targets.width();
                height = targets.height();
                groups = source.workGroups(width, height);
            }
            return groups;
        }

        @Override
        public int program() {
            return source.program().getId();
        }

        @Override
        public boolean indirect() {
            return source.hasIndirectPointer();
        }

        @Override
        public int indirectBuffer() {
            return context.shaderStorageBuffers.glBufferId(source.indirectBuffer());
        }

        @Override
        public long indirectOffset() {
            return source.indirectOffset();
        }

        @Override
        public int groupsX() {
            return groups()[0];
        }

        @Override
        public int groupsY() {
            return groups()[1];
        }

        @Override
        public int groupsZ() {
            return groups()[2];
        }
    }
}
