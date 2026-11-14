package dev.engine_room.flywheel.iris;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class IrisMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> SHARED_MIXINS = Set.of(
            "BlendModeOverrideAccessor", "CommonUniformsMixin", "ComputeSourceMixin", "EntityConversionMixin",
            "IncludeGraphMixin",
            "IrisRenderingPipelineAccessor", "NativeDepthTransformerMixin", "NativeDhUniformsMixin",
            "NativeMatrixUniformsMixin", "NativeReverseZApiMixin", "NativeShadowClearMixin",
            "NativeShadowMatricesMixin",
            "NativeShadowUniformsMixin", "NativeTransformCacheKeyMixin", "ProgramFallbackResolverAccessor",
            "ProgramSetMixin", "ProgramSourceMixin", "ShaderPackMixin", "ShaderPackSourceNamesMixin",
            "ShadowEntitiesMixin", "VisualizationManagerMixin");
    // Resource lookup: classloading Iris here would run before its own mixin plugin.
    static final boolean IRIS_PRESENT = IrisMixinPlugin.class.getClassLoader()
                                                             .getResource("net/irisshaders/iris/Iris.class") != null;
    private static final boolean VOXY_PRESENT = IrisMixinPlugin.class.getClassLoader()
                                                                     .getResource(
                                                                             "me/cortex/voxy/client/core/IrisVoxyRenderPipeline.class") != null;
    private static final boolean DH_PRESENT = IrisMixinPlugin.class.getClassLoader()
                                                                   .getResource(
                                                                           "com/seibel/distanthorizons/api/DhApi.class") != null;

    @Override
    public boolean shouldApplyMixin(String _, String mixinClassName) {
        if (mixinClassName.endsWith(".FarVoxyPipelineMixin") || mixinClassName.endsWith(".VoxyShaderPatchMixin")
                || mixinClassName.endsWith(".FarVoxyWaterMixin")) {
            #if MC < 26.3
            return false;
            #else
            if (!VOXY_PRESENT) return false;
            #endif
        }
        if ((mixinClassName.endsWith(".IrisLodFarMixin") || mixinClassName.endsWith(".FarDhMainMixin")
                || mixinClassName.endsWith(".FarDhOitMixin") || mixinClassName.endsWith(".FarDhWaterMixin")
                || mixinClassName.endsWith(".DHCompatAccessor")
                || mixinClassName.endsWith(".NativeDhPassProjectionMixin")
                || mixinClassName.endsWith(".NativeDhGenericProjectionMixin"))
                && !DH_PRESENT) {
            return false;
        }
        return IRIS_PRESENT && (!IrisRuntime.EXPERIMENTAL
                || SHARED_MIXINS.contains(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)));
    }

    @Override
    public void onLoad(String _) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> _, Set<String> _) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String _, ClassNode _, String _, IMixinInfo _) {
    }

    @Override
    public void postApply(String _, ClassNode _, String _, IMixinInfo _) {
    }
}
