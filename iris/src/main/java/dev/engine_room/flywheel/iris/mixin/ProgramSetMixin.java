package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.compile.ContractProgramSet;
import dev.engine_room.flywheel.iris.compile.ContractProperties;
import dev.engine_room.flywheel.iris.compile.ContractShaderPack;
import dev.engine_room.flywheel.iris.compile.ForwardOit;
import dev.engine_room.flywheel.iris.compile.LightDemandSources;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.compile.patches.ForwardOitShaders;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Mixin(ProgramSet.class)
abstract class ProgramSetMixin implements ContractProgramSet {
    @Unique
    private final Map<ContractProgram, ProgramSource> flywheel$contract = new EnumMap<>(ContractProgram.class);

    // Resolved on first use: the base set is built before the pack parses its adapter's OIT spec.
    @Unique
    private @Nullable ContractProperties flywheel$nativeOit;
    @Unique
    private @Nullable ProgramSource flywheel$nativeWater;
    @Unique
    private @Nullable ShaderPack flywheel$pack;

    @Unique
    private @Nullable DeferredOitProfile flywheel$deferredOit;

    @Unique
    private @Nullable ForwardOit flywheel$forward;

    @Unique
    private @Nullable Map<String, String> flywheel$patchedFragments;

    @Unique
    private @Nullable LightDemandSources flywheel$lightDemand;

    @Shadow
    private static ProgramSource readProgramSource(AbsolutePackPath _,
                                                   Function<AbsolutePackPath, String> _, String _,
                                                   ProgramSet _, ShaderProperties _,
                                                   boolean _) {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$readContract(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
                                       ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo _) {
        ProgramSet self = (ProgramSet) (Object) this;
        for (ContractProgram program : ContractProgram.values()) {
            ProgramSource source = readProgramSource(directory, sourceProvider, program.sourceName, self,
                    shaderProperties, false);
            if (source.isValid()) {
                flywheel$contract.put(program, source);
            }
        }
        ProgramSource translucent = flywheel$contract.get(ContractProgram.GBUFFERS_TRANSLUCENT);
        if (translucent != null && translucent.getFragmentSource().orElseThrow()
                                              .contains(ContractPatches.NATIVE_TRANSLUCENT)) {
            flywheel$nativeWater = self.get(ProgramId.Water).orElseThrow();
            flywheel$pack = pack;
        }
        DeferredOitProfile deferred = pack.flywheel$deferredOit();
        if (deferred != null) {
            try {
                flywheel$patchedFragments = new HashMap<>(deferred.fragments(self, flywheel$contract));
                if (!IrisRuntime.EXPERIMENTAL) {
                    flywheel$lightDemand = deferred.lightDemand(self, flywheel$patchedFragments);
                    flywheel$patchedFragments.replaceAll((_, source) -> DeferredOitProfile.enableLightDemand(source));
                }
                flywheel$deferredOit = deferred;
                FlwBackend.LOGGER.info("Deferred OIT adapter ready: {} {}", deferred, directory);
            } catch (UnsupportedOperationException e) {
                deferred.rejected(self);
                FlwBackend.LOGGER.warn("Deferred OIT adapter rejected; retaining native translucency: {}",
                        e.getMessage());
            }
        }
        if (pack.flywheel$forwardProfile() != null) {
            var forward = new ForwardOit(pack.flywheel$forwardProfile(), self);
            var originals = new ArrayList<ProgramSource>();
            originals.add(self.get(ProgramId.Water).orElseThrow());
            for (var id : List.of(ContractProgram.GBUFFERS_TRANSLUCENT, ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT)) {
                if (flywheel$contract.containsKey(id)) originals.add(flywheel$contract.get(id));
            }
            for (var source : originals) {
                var variant = readProgramSource(directory, sourceProvider, ForwardOit.PREFIX + source.getName(), self,
                        shaderProperties, false);
                String fragment = ForwardOitShaders.material(variant.getFragmentSource().orElseThrow(), forward.profile,
                        self, forward.waterBuffers().length);
                forward.source(source, new ProgramSource(source.getName(), variant.getVertexSource().orElseThrow(),
                        null, null, null, fragment, self, shaderProperties, BlendModeOverride.OFF));
            }
            flywheel$forward = forward;
            FlwBackend.LOGGER.info("Forward wavelet OIT adapter ready: {} {}", forward.profile, directory);
        }
    }

    @Override
    public boolean flywheel$hasContract(ContractProgram program) {
        return flywheel$contract.containsKey(program);
    }

    @Override
    public @Nullable ContractProperties flywheel$nativeOit() {
        if (flywheel$nativeWater != null) {
            ContractShaderPack pack = flywheel$pack;
            flywheel$nativeOit = ContractProperties.nativeForwardOit(flywheel$nativeWater, pack.flywheel$forwardOit(),
                    pack.flywheel$contractProperties().oit(false));
            flywheel$nativeWater = null;
            flywheel$pack = null;
        }
        return flywheel$nativeOit;
    }

    @Override
    public @Nullable DeferredOitProfile flywheel$deferredOit() {
        return flywheel$deferredOit;
    }

    @Override
    public @Nullable ForwardOit flywheel$forwardOit() {
        return flywheel$forward;
    }

    @Override
    public @Nullable String flywheel$patchedFragment(String program) {
        return flywheel$patchedFragments == null ? null : flywheel$patchedFragments.get(program);
    }

    @Override
    public @Nullable LightDemandSources flywheel$lightDemand() {
        return flywheel$lightDemand;
    }

    @Override
    public @Nullable ProgramSource flywheel$contractSource(ContractProgram program) {
        for (ContractProgram id = program; id != null; id = id.base) {
            ProgramSource source = flywheel$contract.get(id);
            if (source != null) {
                return source;
            }
        }
        return null;
    }
}
