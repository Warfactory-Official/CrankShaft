package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass")
public interface CompositePassAccessor {
    @Accessor("name")
    String flywheel$name();

    @Accessor("stageReadsFromAlt")
    ImmutableSet<Integer> flywheel$readAlt();

    @Accessor("drawBuffers")
    int[] flywheel$drawBuffers();

    @Accessor("framebuffer")
    GlFramebuffer flywheel$framebuffer();
}
