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
    private static final String MIXIN_BETTER_BLOCK_ENTITIES = "dev.engine_room.flywheel.impl.mixin.visualmanage.BetterBlockEntitiesMixin";
    private static final boolean BETTER_BLOCK_ENTITIES_PRESENT = classPresent(
            "betterblockentities.client.render.immediate.blockentity.extentions.BlockEntityExt");
    private static final String MIXIN_LAMBDYNLIGHTS = "dev.engine_room.flywheel.impl.mixin.LambDynLightsAccessor";
    private static final boolean LAMBDYNLIGHTS_PRESENT = classPresent("dev.lambdaurora.lambdynlights.LambDynLights");
    private static final String MIXIN_POLYTONE = "dev.engine_room.flywheel.impl.mixin.Polytone";
    private static final boolean POLYTONE_PRESENT = classPresent("net.mehvahdjukaar.polytone.Polytone");
    private static final String MIXIN_EMF = "dev.engine_room.flywheel.impl.mixin.Emf";
    private static final boolean EMF_PRESENT = classPresent("traben.entity_model_features.EMFManager");

    private static boolean classPresent(String name) {
        return FlwImplMixinPlugin.class.getClassLoader().getResource(name.replace('.', '/') + ".class") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (MIXIN_CHUNK_SECTIONS_TO_RENDER.equals(mixinClassName)) {
            return !SODIUM_PRESENT;
        }
        if (MIXIN_BETTER_BLOCK_ENTITIES.equals(mixinClassName)) {
            return BETTER_BLOCK_ENTITIES_PRESENT;
        }
        if (MIXIN_LAMBDYNLIGHTS.equals(mixinClassName)) {
            return LAMBDYNLIGHTS_PRESENT;
        }
        if (mixinClassName.startsWith(MIXIN_POLYTONE)) {
            return POLYTONE_PRESENT;
        }
        if (mixinClassName.startsWith(MIXIN_EMF)) {
            return EMF_PRESENT;
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
