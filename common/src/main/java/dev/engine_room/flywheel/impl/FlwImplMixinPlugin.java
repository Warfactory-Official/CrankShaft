package dev.engine_room.flywheel.impl;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class FlwImplMixinPlugin implements IMixinConfigPlugin {
    private static final String MIXIN_CHUNK_SECTIONS_TO_RENDER = "dev.engine_room.flywheel.impl.mixin.MixinChunkSectionsToRender";
    private static final boolean SODIUM_PRESENT = classPresent(
            "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");

    private static boolean classPresent(String name) {
        return FlwImplMixinPlugin.class.getClassLoader().getResource(name.replace('.', '/') + ".class") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (MIXIN_CHUNK_SECTIONS_TO_RENDER.equals(mixinClassName)) {
            return !SODIUM_PRESENT;
        }
        return true;
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
