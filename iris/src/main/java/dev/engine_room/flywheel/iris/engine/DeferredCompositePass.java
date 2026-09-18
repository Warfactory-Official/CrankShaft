package dev.engine_room.flywheel.iris.engine;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.mixinterface.CustomPass;

/**
 * Borrowed Iris pass state; valid on the render thread until its CompositeRenderer is destroyed.
 */
public interface DeferredCompositePass extends CustomPass {
    String flywheel$name();

    Program flywheel$program();

    ImmutableSet<Integer> flywheel$readAlt();

    ImmutableSet<Integer> flywheel$mipmaps();

    int[] flywheel$drawBuffers();

    GlFramebuffer flywheel$framebuffer();
}
