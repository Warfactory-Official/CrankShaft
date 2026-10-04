package dev.engine_room.flywheel.iris.engine;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.mixinterface.CustomPass;
import org.jspecify.annotations.Nullable;

/**
 * Borrowed Iris pass state; valid on the render thread until its CompositeRenderer is destroyed.
 */
public interface DeferredCompositePass extends CustomPass {
    String flywheel$name();

    @Nullable Program flywheel$program();

    ComputeProgram[] flywheel$computes();

    int flywheel$viewWidth();

    int flywheel$viewHeight();

    ViewportData flywheel$viewportScale();

    ImmutableSet<Integer> flywheel$readAlt();

    ImmutableSet<Integer> flywheel$mipmaps();

    int[] flywheel$drawBuffers();

    GlFramebuffer flywheel$framebuffer();
}
