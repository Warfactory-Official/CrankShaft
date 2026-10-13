package dev.engine_room.flywheel.iris.experimental.mixin;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.pipeline.programs.CompositeProgram;
import net.irisshaders.iris.pipeline.programs.ComputeProgram;
import net.irisshaders.iris.targets.ColorAttachmentPlan;
import net.irisshaders.iris.targets.RenderFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass")
public interface ExperimentalCompositePassMixin {
    @Accessor("name")
    String flywheel$name();

    @Accessor("program")
    CompositeProgram flywheel$program();

    @Accessor("computes")
    ComputeProgram[] flywheel$computes();

    @Accessor("framebuffer")
    RenderFramebuffer flywheel$framebuffer();

    @Accessor("attachments")
    ColorAttachmentPlan flywheel$attachments();

    @Accessor("viewportScale")
    ViewportData flywheel$viewport();

    @Accessor("stageReadsFromAlt")
    ImmutableSet<Integer> flywheel$readAlt();

    @Accessor("mipmappedBuffers")
    ImmutableSet<Integer> flywheel$mipmaps();

    @Accessor("drawBuffers")
    int[] flywheel$drawBuffers();
}
