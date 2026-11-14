package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.iris.compile.FarDhOwner;
import dev.engine_room.flywheel.iris.compile.FarDhProgram;
import dev.engine_room.flywheel.iris.compile.FarDhShaders;
import dev.engine_room.flywheel.iris.compile.FarLayerDhProgram;
import dev.engine_room.flywheel.iris.compile.FarLayerDhShaders;
import dev.engine_room.flywheel.iris.compile.FarLayerIrisBridge;
import dev.engine_room.flywheel.iris.compile.FarHostOit;
import dev.engine_room.flywheel.iris.compile.FarDhOit;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.iris.compile.FarStaticDhProgram;
import dev.engine_room.flywheel.iris.compile.FarStaticDhShaders;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * The opaque DH program's uniforms, pack samplers and image bindings also own its retained surface draw.
 */
@Mixin(IrisLodRenderProgram.class)
abstract class IrisLodFarMixin implements FarDhOwner {
    @Shadow
    @Final
    private int id;
    @Unique
    private FarDhProgram flywheel$far;
    @Unique
    private FarStaticDhProgram flywheel$staticFar;
    @Unique
    private FarLayerDhProgram flywheel$layeredFar;
    @Unique
    private FarDhOit flywheel$hostOit;

    @WrapOperation(method = "createProgram", at = @At(value = "NEW",
            target = "net/irisshaders/iris/compat/dh/IrisLodRenderProgram"))
    private static IrisLodRenderProgram flywheel$createFar(String name, boolean shadow, boolean translucent,
                                                           BlendModeOverride blend, BufferBlendOverride[] blends,
                                                           String vertex, String tessControl,
                                                           String tessEval, String geometry, String fragment,
                                                           CustomUniforms uniforms, IrisRenderingPipeline pipeline,
                                                           Operation<IrisLodRenderProgram> original, String sourceName,
                                                           boolean sourceShadow, boolean sourceTranslucent,
                                                           ProgramSource source, CustomUniforms sourceUniforms,
                                                           IrisRenderingPipeline sourcePipeline) {
        IrisLodRenderProgram program = original.call(name, shadow, translucent, blend, blends, vertex, tessControl,
                tessEval, geometry, fragment, uniforms, pipeline);
        if ((shadow || translucent) && GlCompat.SUPPORTS_INDIRECT && GlCompat.SUPPORTS_SHADER_DRAW_PARAMETERS) {
            if (tessControl != null || tessEval != null || geometry != null)
                throw new IllegalStateException("Layered DH surfaces require vertex/fragment stages: " + name);
            var stages = FarLayerDhShaders.patch(vertex, fragment);
            IrisLodRenderProgram layered = original.call(name + FarLayerDhProgram.SUFFIX, shadow, translucent, blend,
                    blends,
                    stages.vertex(), null, null, null, stages.fragment(), uniforms, pipeline);
            program.flywheel$layeredFar(new FarLayerDhProgram(layered,
                    ((IrisLodFarMixin) (Object) layered).id, source.getDirectives().getDrawBuffers(), pipeline,
                    stages.materialMaps(), shadow));
        }
        if (!shadow && translucent) {
            var graph = FarHostOit.create(source, pipeline);
            if (graph != null) {
                var water = new IrisLodRenderProgram[graph.passCount()];
                var handles = new int[graph.passCount()];
                var retained = new FarLayerDhProgram[graph.passCount()];
                var stages = FarLayerDhShaders.patch(vertex, fragment);
                for (int pass = 0; pass < graph.passCount(); pass++) {
                    water[pass] = original.call(name + FarHostOit.SUFFIX + pass, false, true, blend, blends,
                            vertex, null, null, null, graph.fragment(fragment, pass), uniforms, pipeline);
                    handles[pass] = ((IrisLodFarMixin) (Object) water[pass]).id;
                    var far = original.call(name + FarHostOit.SUFFIX + pass + FarLayerDhProgram.SUFFIX,
                            false, true, blend, blends, stages.vertex(), null, null, null,
                            graph.fragment(stages.fragment(), pass, true), uniforms, pipeline);
                    retained[pass] = new FarLayerDhProgram(far, ((IrisLodFarMixin) (Object) far).id,
                            source.getDirectives().getDrawBuffers(), pipeline, stages.materialMaps(), false);
                    retained[pass].hostOit(graph);
                }
                program.flywheel$hostOit(new FarDhOit(graph, water, handles, retained));
            }
        }
        if (!shadow && !translucent) {
            if (tessControl != null || tessEval != null || geometry != null) {
                throw new IllegalStateException("Far DH surfaces require a vertex/fragment terrain program: " + name);
            }
            IrisLodRenderProgram far = original.call(name + FarDhProgram.SUFFIX, false, false, blend, blends,
                    FarDhShaders.vertex(vertex), null, null, null, FarDhShaders.fragment(fragment),
                    uniforms, pipeline);
            program.flywheel$far(new FarDhProgram(far,
                    ((IrisLodFarMixin) (Object) far).id, source.getDirectives().getDrawBuffers(), pipeline));
            if (GlCompat.SUPPORTS_INDIRECT && GlCompat.SUPPORTS_SHADER_DRAW_PARAMETERS) {
                var stages = FarStaticDhShaders.patch(vertex, fragment);
                if (stages.materialMaps() && !GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                    throw new IllegalStateException("Static DH material maps require GL_ARB_bindless_texture");
                }
                IrisLodRenderProgram frozen = original.call(name + FarStaticDhProgram.SUFFIX, false, false, blend,
                        blends, stages.vertex(), null, null, null, stages.fragment(), uniforms, pipeline);
                program.flywheel$staticFar(new FarStaticDhProgram(frozen,
                        ((IrisLodFarMixin) (Object) frozen).id, source.getDirectives().getDrawBuffers().length,
                        pipeline, stages.materialMaps()));
            }
        }
        return program;
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/ProgramSamplers;builder(ILjava/util/Set;)Lnet/irisshaders/iris/gl/program/ProgramSamplers$Builder;"),
            index = 1)
    private Set<Integer> flywheel$reserveSurfaceSamplers(Set<Integer> reserved,
                                                         @Local(argsOnly = true, ordinal = 0) String name) {
        if (name.contains(FarHostOit.SUFFIX)) {
            var builder = ImmutableSet.<Integer>builder().addAll(reserved);
            for (int unit = 8; unit < 20; unit++) builder.add(unit);
            return builder.build();
        }
        if (name.endsWith(FarStaticDhProgram.SUFFIX)) {
            return ImmutableSet.<Integer>builder().addAll(reserved).add(10, 11, 12).build();
        }
        return name.endsWith(FarDhProgram.SUFFIX)
                ? ImmutableSet.<Integer>builder().addAll(reserved).add(10, 11, 12, 13, 14, 15).build() : reserved;
    }

    @Inject(method = "fillUniformData", at = @At("RETURN"))
    private void flywheel$prepareFar(Matrix4fc projection, Matrix4fc modelView, int worldYOffset, float partialTicks,
                                     CallbackInfo ci) {
        if (flywheel$far != null && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            flywheel$far.prepareLod(projection, modelView, worldYOffset, partialTicks);
        }
        if (flywheel$layeredFar != null) {
            flywheel$layeredFar.prepareLod(projection, modelView, worldYOffset, partialTicks);
            if (!flywheel$layeredFar.shadow() && !ShadowRenderingState.areShadowsCurrentlyBeingRendered())
                FarLayerIrisBridge.dhTranslucent();
        }
        FarDhOit.bind((IrisLodRenderProgram) (Object) this);
        if (flywheel$staticFar != null && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            flywheel$staticFar.prepareLod(projection, modelView, worldYOffset, partialTicks);
        }
    }

    @Inject(method = "bind", at = @At("RETURN"))
    private void flywheel$bindHostOit(CallbackInfo ci) {
        FarDhOit.bind((IrisLodRenderProgram) (Object) this);
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void flywheel$freeFar(CallbackInfo ci) {
        if (flywheel$far != null) flywheel$far.delete();
        if (flywheel$staticFar != null) flywheel$staticFar.close();
        if (flywheel$layeredFar != null) flywheel$layeredFar.close();
        if (flywheel$hostOit != null) flywheel$hostOit.close();
    }

    @Override
    public void flywheel$far(FarDhProgram program) {
        flywheel$far = program;
    }

    @Override
    public FarDhProgram flywheel$far() {
        return flywheel$far;
    }

    @Override
    public void flywheel$staticFar(FarStaticDhProgram program) {
        flywheel$staticFar = program;
    }

    @Override
    public FarStaticDhProgram flywheel$staticFar() {
        return flywheel$staticFar;
    }

    @Override
    public void flywheel$layeredFar(FarLayerDhProgram program) {
        flywheel$layeredFar = program;
    }

    @Override
    public FarLayerDhProgram flywheel$layeredFar() {
        return flywheel$layeredFar;
    }

    @Override
    public void flywheel$hostOit(FarDhOit graph) {
        flywheel$hostOit = graph;
    }

    @Override
    public FarDhOit flywheel$hostOit() {
        return flywheel$hostOit;
    }
}
