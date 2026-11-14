package dev.engine_room.flywheel.impl;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class FlwImplMixinPlugin implements IMixinConfigPlugin {
    private static final boolean NEOFORGE_PRESENT = classPresent("net.neoforged.fml.loading.FMLLoader");
    #if MC < 26.3
    private static final String MIXIN_CHUNK_SECTIONS_TO_RENDER = "dev.engine_room.flywheel.impl.mixin.MixinChunkSectionsToRender";
    private static final boolean SODIUM_PRESENT = classPresent(
            "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
    #endif
    private static final String MIXIN_BETTER_BLOCK_ENTITIES = "dev.engine_room.flywheel.impl.mixin.visualmanage.BetterBlockEntitiesMixin";
    private static final boolean BETTER_BLOCK_ENTITIES_PRESENT = classPresent(
            "betterblockentities.client.render.immediate.blockentity.extentions.BlockEntityExt");
    private static final String MIXIN_LAMBDYNLIGHTS = "dev.engine_room.flywheel.impl.mixin.LambDynLightsAccessor";
    private static final boolean LAMBDYNLIGHTS_PRESENT = classPresent("dev.lambdaurora.lambdynlights.LambDynLights");
    private static final String MIXIN_POLYTONE = "dev.engine_room.flywheel.impl.mixin.Polytone";
    private static final boolean POLYTONE_PRESENT = classPresent("net.mehvahdjukaar.polytone.Polytone");
    private static final String MIXIN_EMF = "dev.engine_room.flywheel.impl.mixin.Emf";
    private static final boolean EMF_PRESENT = classPresent("traben.entity_model_features.EMFManager");
    private static final String MIXIN_VOXY = "dev.engine_room.flywheel.impl.mixin.Voxy";
    private static final boolean VOXY_PRESENT = classPresent("me.cortex.voxy.client.core.NormalRenderPipeline");
    private static final String MIXIN_DISTANT_HORIZONS = "dev.engine_room.flywheel.impl.mixin.Dh";
    private static final boolean DISTANT_HORIZONS_PRESENT = classPresent(
            "com.seibel.distanthorizons.core.api.internal.ClientApi");

    private static boolean classPresent(String name) {
        return FlwImplMixinPlugin.class.getClassLoader().getResource(name.replace('.', '/') + ".class") != null;
    }

    @Override
    public boolean shouldApplyMixin(String _, String mixinClassName) {
        if (mixinClassName.equals("dev.engine_room.flywheel.impl.mixin.SectionCompilerReceiverMixin")) {
            return !NEOFORGE_PRESENT;
        }
        #if MC < 26.3
        if (MIXIN_CHUNK_SECTIONS_TO_RENDER.equals(mixinClassName)) {
            return !SODIUM_PRESENT;
        }
        #endif
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
        if (mixinClassName.equals("dev.engine_room.flywheel.impl.mixin.DhLevelRendererMixin")) {
            return DISTANT_HORIZONS_PRESENT && !NEOFORGE_PRESENT;
        }
        if (mixinClassName.startsWith(MIXIN_DISTANT_HORIZONS)) {
            return DISTANT_HORIZONS_PRESENT;
        }
        if (mixinClassName.startsWith(MIXIN_VOXY)) {
            return VOXY_PRESENT;
        }
        return true;
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
