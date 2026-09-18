package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.jspecify.annotations.Nullable;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

@Mixin(IrisRenderingPipeline.class)
public interface IrisRenderingPipelineAccessor {
    @Accessor("renderTargets")
    RenderTargets flywheel$renderTargets();

    @Accessor("resolver")
    ProgramFallbackResolver flywheel$resolver();

    @Accessor("pack")
    ShaderPack flywheel$pack();

    @Accessor("shadowTargetsSupplier")
    Supplier<ShadowRenderTargets> flywheel$shadowTargets();

    @Accessor("shadowDirectives")
    PackShadowDirectives flywheel$shadowDirectives();

    @Accessor("shaderStorageBufferHolder")
    @Nullable ShaderStorageBufferHolder flywheel$shaderStorageBuffers();
}
