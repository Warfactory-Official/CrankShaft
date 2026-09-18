package dev.engine_room.flywheel.iris;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class IrisMixinPlugin implements IMixinConfigPlugin {
    // Resource lookup: classloading Iris here would run before its own mixin plugin.
    static final boolean IRIS_PRESENT = IrisMixinPlugin.class.getClassLoader()
                                                              .getResource("net/irisshaders/iris/Iris.class") != null;

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return IRIS_PRESENT;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
