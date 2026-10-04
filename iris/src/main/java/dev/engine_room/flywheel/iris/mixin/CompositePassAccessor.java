package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableSet;
import dev.engine_room.flywheel.iris.engine.DeferredCompositePass;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass")
public interface CompositePassAccessor extends DeferredCompositePass {
    @Accessor("name")
    String flywheel$name();

    @Accessor("program")
    @Nullable Program flywheel$program();

    @Accessor("computes")
    ComputeProgram[] flywheel$computes();

    @Accessor("viewWidth")
    int flywheel$viewWidth();

    @Accessor("viewHeight")
    int flywheel$viewHeight();

    @Accessor("viewportScale")
    ViewportData flywheel$viewportScale();

    @Accessor("stageReadsFromAlt")
    ImmutableSet<Integer> flywheel$readAlt();

    @Accessor("mipmappedBuffers")
    ImmutableSet<Integer> flywheel$mipmaps();

    @Accessor("drawBuffers")
    int[] flywheel$drawBuffers();

    @Accessor("framebuffer")
    GlFramebuffer flywheel$framebuffer();
}
